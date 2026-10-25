package com.onesixromcom.movdow

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Downloads one queue item:
 * resolve playlist -> download .ts segments (resumable) -> join -> mp4 with fixed audio ([Remuxer])
 * -> copy to the download folder with moov in front ([Mp4FastStart]) -> subtitles.
 * Replaces the wget/curl + ffmpeg part of movie.sh.
 */
class Downloader(private val context: Context) {

    private val userAgent = WebSettings.getDefaultUserAgent(context)

    suspend fun download(item: DownloadItem, onProgress: (done: Int, total: Int, stage: String?) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            AppLog.i("▶ ${item.title} (${item.voice}, ${item.quality}p)")
            onProgress(item.done, item.total, "Getting playlist…")
            val playlist = PlaylistResolver(Http.client, userAgent)
                .resolve(item.iframeUrl, item.playerKey, item.referer, item.quality)
            val total = playlist.segments.size
            if (total == 0) throw IOException("Playlist has no segments")

            val dir = DownloadManager.tempDir(item.id).apply { mkdirs() }
            val joined = File(dir, "joined.ts")
            val muxed = File(dir, "muxed.mp4")

            if (muxed.exists() || joined.exists()) {
                AppLog.i("Segments are already joined")
            } else {
                val existing = (0 until total).count { segmentFile(dir, it).exists() }
                if (existing > 0) AppLog.i("Resuming: $existing / $total segments already downloaded")
                val logStep = maxOf(1, total / 10)
                playlist.segments.forEachIndexed { i, url ->
                    ensureActive()
                    val file = segmentFile(dir, i)
                    if (!file.exists()) downloadSegment(url, playlist.playerUrl, file)
                    onProgress(i + 1, total, null)
                    if ((i + 1) % logStep == 0 && i + 1 < total) AppLog.i("${item.title}: ${(i + 1) * 100 / total}% (${i + 1} / $total)")
                }
                AppLog.i("${item.title}: all $total segments downloaded, joining…")

                onProgress(total, total, "Joining segments…")
                val part = File(dir, "joined.part")
                part.outputStream().use { out ->
                    for (i in 0 until total) {
                        ensureActive()
                        segmentFile(dir, i).inputStream().use { it.copyTo(out) }
                    }
                }
                part.renameTo(joined)
                for (i in 0 until total) segmentFile(dir, i).delete()
            }

            if (!muxed.exists()) {
                val duration = formatTime(playlist.durationUs)
                onProgress(total, total, if (item.fixAudio) "Fixing audio…" else "Converting to mp4…")
                if (!remux(joined, muxed, dir, item.fixAudio) { position ->
                        onProgress(total, total, "Fixing audio… ${formatTime(position)} / $duration")
                    }
                ) {
                    // Not convertible: keep the .ts as is.
                    val folder = targetFolder(item)
                    saveTs(joined, folder, item.fileName)
                    saveSubtitlesIfNeeded(item, playlist, folder, total, onProgress)
                    dir.deleteRecursively()
                    return@withContext
                }
                joined.delete()
            }

            val folder = targetFolder(item)
            onProgress(total, total, "Saving video…")
            AppLog.i("Saving \"${item.fileName}.mp4\" to ${item.subfolder?.let { "folder \"$it\"" } ?: "the download folder"}…")
            val mp4 = createFile(folder, "${item.fileName}.mp4", "video/mp4")
            try {
                val moved = context.contentResolver.openOutputStream(mp4.uri)!!.use { Mp4FastStart.write(muxed, it) }
                if (!moved) AppLog.i("faststart skipped, file copied as is")
            } catch (e: Exception) {
                mp4.delete()
                throw e
            }
            AppLog.i("Saved ${item.fileName}.mp4 (${muxed.length() / (1024 * 1024)} MB)")
            saveSubtitlesIfNeeded(item, playlist, folder, total, onProgress)

            dir.deleteRecursively()
        }

    private fun saveSubtitlesIfNeeded(
        item: DownloadItem,
        playlist: ResolvedPlaylist,
        folder: DocumentFile,
        total: Int,
        onProgress: (done: Int, total: Int, stage: String?) -> Unit,
    ) {
        if (item.subtitles && playlist.subtitles.isNotEmpty()) {
            onProgress(total, total, "Saving subtitles…")
            saveSubtitles(playlist.subtitles, folder, item.fileName, item.transliterate)
        }
    }

    /**
     * joined.ts -> mp4 in the temp folder. Fallbacks: audio fix -> plain remux -> false (keep .ts).
     */
    private suspend fun remux(input: File, output: File, workDir: File, fixAudio: Boolean, onAudioProgress: (Long) -> Unit): Boolean {
        val part = File(workDir, "muxed.part")
        val attempts = if (fixAudio) listOf(true, false) else listOf(false)
        for (audio in attempts) {
            try {
                AppLog.i(if (audio) "Converting to mp4, re-encoding audio (AAC 128 kbps)…" else "Converting to mp4 (copy)…")
                Remuxer(fixAudio = audio, onAudioProgress = onAudioProgress).remux(input, part, workDir)
                part.renameTo(output)
                AppLog.i("mp4 ready: ${output.length() / (1024 * 1024)} MB")
                return true
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                AppLog.e(if (audio) "Audio fix failed, trying plain remux" else "mp4 remux failed, saving .ts instead", e)
            }
        }
        return false
    }

    private fun saveTs(joined: File, folder: DocumentFile, name: String) {
        val ts = createFile(folder, "$name.ts", "video/mp2t")
        context.contentResolver.openOutputStream(ts.uri)!!.use { out -> joined.inputStream().use { it.copyTo(out) } }
        AppLog.i("Saved $name.ts (${joined.length() / (1024 * 1024)} MB)")
    }

    private fun formatTime(us: Long): String {
        val seconds = us / 1_000_000
        return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun segmentFile(dir: File, index: Int) = File(dir, "%05d.ts".format(index))

    private suspend fun downloadSegment(url: String, referer: String, file: File) {
        val part = File(file.path + ".part")
        var lastError: Exception? = null
        repeat(5) { attempt ->
            coroutineContext.ensureActive()
            try {
                Http.client.newCall(request(url, referer)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    part.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                }
                if (!part.renameTo(file)) throw IOException("Can't save ${file.name}")
                return
            } catch (e: IOException) {
                lastError = e
                AppLog.e("Segment ${file.name}, attempt ${attempt + 1} / 5", e)
                delay(1000L * (attempt + 1))
            }
        }
        throw IOException("Segment download failed: ${lastError?.message}", lastError)
    }

    private fun request(url: String, referer: String) = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Referer", referer)
        .build()

    private fun targetFolder(item: DownloadItem): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(item.folderUri))?.takeIf { it.canWrite() }
            ?: throw IOException("Download folder is not accessible. Choose it again in Settings.")
        val name = item.subfolder ?: return root
        return root.findFile(name)?.takeIf { it.isDirectory }
            ?: root.createDirectory(name)
            ?: throw IOException("Can't create folder $name")
    }

    private fun createFile(folder: DocumentFile, name: String, mime: String): DocumentFile {
        folder.findFile(name)?.delete()
        return folder.createFile(mime, name) ?: throw IOException("Can't create $name")
    }

    // Subtitles are optional: a failed one doesn't fail the video.
    private fun saveSubtitles(subtitles: List<Subtitle>, folder: DocumentFile, name: String, transliterate: Boolean) {
        for (subtitle in subtitles) {
            try {
                val vtt = Http.client.newCall(request(subtitle.url, subtitle.url)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body!!.string()
                }
                val lang = (if (transliterate) Transliteration.fileName(subtitle.name) else DownloadItem.safeName(subtitle.name))
                    .ifBlank { "sub" }
                // octet-stream: keep the .srt name as is (no extra extension from the mime type).
                val file = createFile(folder, "$name.$lang.srt", "application/octet-stream")
                context.contentResolver.openOutputStream(file.uri)!!.use { it.write(SubtitleConverter.vttToSrt(vtt).toByteArray()) }
                AppLog.i("Saved subtitles $name.$lang.srt")
            } catch (e: IOException) {
                AppLog.e("Subtitles ${subtitle.name} failed", e)
            }
        }
    }
}
