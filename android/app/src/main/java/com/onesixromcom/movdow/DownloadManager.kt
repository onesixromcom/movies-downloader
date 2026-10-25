package com.onesixromcom.movdow

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

data class DownloadItem(
    val id: String,
    /** Shown in the queue. */
    val title: String,
    val voice: String,
    /** Output file name without extension. */
    val fileName: String,
    /** File names in Latin letters (subtitles too), see [Transliteration]. */
    val transliterate: Boolean,
    /** Subfolder inside the download folder, null for the folder itself. */
    val subfolder: String?,
    val folderUri: String,
    /** Player page (or m3u8). */
    val iframeUrl: String,
    /** Episode inside the player's playlist, see [PlaylistItem.key]. */
    val playerKey: String?,
    val referer: String,
    val quality: String,
    val subtitles: Boolean,
    /** Re-encode audio with gaps filled, see [Remuxer]. */
    val fixAudio: Boolean,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    /** Current step when it's not segments downloading, e.g. "Saving mp4…". */
    val stage: String? = null,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("voice", voice)
        .put("fileName", fileName)
        .put("transliterate", transliterate)
        .put("subfolder", subfolder)
        .put("folderUri", folderUri)
        .put("iframeUrl", iframeUrl)
        .put("playerKey", playerKey)
        .put("referer", referer)
        .put("quality", quality)
        .put("subtitles", subtitles)
        .put("fixAudio", fixAudio)
        .put("status", status.name)
        .put("done", done)
        .put("total", total)
        .put("error", error)

    companion object {
        fun fromJson(json: JSONObject) = DownloadItem(
            id = json.getString("id"),
            title = json.getString("title"),
            voice = json.optString("voice"),
            fileName = json.getString("fileName"),
            transliterate = json.optBoolean("transliterate"),
            subfolder = json.optString("subfolder").takeIf { json.has("subfolder") && it.isNotEmpty() },
            folderUri = json.getString("folderUri"),
            iframeUrl = json.getString("iframeUrl"),
            playerKey = json.optString("playerKey").takeIf { json.has("playerKey") && it.isNotEmpty() },
            referer = json.getString("referer"),
            quality = json.getString("quality"),
            subtitles = json.optBoolean("subtitles", true),
            fixAudio = json.optBoolean("fixAudio", true),
            // An interrupted download continues from the downloaded segments.
            status = DownloadStatus.valueOf(json.getString("status"))
                .let { if (it == DownloadStatus.DOWNLOADING) DownloadStatus.QUEUED else it },
            done = json.optInt("done"),
            total = json.optInt("total"),
            error = json.optString("error").takeIf { json.has("error") && it.isNotEmpty() },
        )

        /** File and folder names safe for any storage (FAT on USB drives too). */
        fun safeName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim().trimEnd('.').take(120).ifBlank { "video" }
    }
}

/** Download queue: one item at a time, persisted to queue.json, resumable. */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private lateinit var appContext: Context
    private var worker: Job? = null

    @Volatile
    private var current: Pair<String, Job>? = null

    private val queueFile get() = File(appContext.filesDir, "queue.json")

    fun init(context: Context) {
        synchronized(lock) {
            if (::appContext.isInitialized) return
            appContext = context.applicationContext
            load()
        }
    }

    /** Temp folder for segments of the item. */
    fun tempDir(id: String): File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "segments/$id")

    fun add(newItems: List<DownloadItem>) {
        _items.update { it + newItems }
        save()
        start()
    }

    fun setPaused(paused: Boolean) {
        AppLog.i(if (paused) "Queue paused" else "Queue resumed")
        _paused.value = paused
        save()
        if (paused) current?.second?.cancel() else start()
    }

    fun retry(id: String) {
        AppLog.i("Retry: ${_items.value.firstOrNull { it.id == id }?.title}")
        update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        save()
        start()
    }

    fun remove(id: String) {
        val active = current
        AppLog.i("Removed from the queue: ${_items.value.firstOrNull { it.id == id }?.title}")
        _items.update { list -> list.filterNot { it.id == id } }
        // The running item's segments are deleted by the worker once it stops.
        if (active?.first == id) active.second.cancel() else tempDir(id).deleteRecursively()
        save()
    }

    fun removeAll() {
        val active = current
        val ids = _items.value.map { it.id }
        AppLog.i("Removed all ${ids.size} items from the queue")
        _items.value = emptyList()
        active?.second?.cancel()
        ids.filter { it != active?.first }.forEach { tempDir(it).deleteRecursively() }
        save()
    }

    /** Start processing the queue (and the foreground service) if there is something to download. */
    fun start() {
        if (_paused.value || _items.value.none { it.status == DownloadStatus.QUEUED }) return
        DownloadService.start(appContext)
        synchronized(lock) {
            if (worker != null) return
            worker = scope.launch { loop() }
        }
    }

    private suspend fun loop() {
        while (true) {
            val item = nextItem() ?: return
            update(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
            val job = scope.launch {
                try {
                    Downloader(appContext).download(item) { done, total, stage ->
                        update(item.id) { it.copy(done = done, total = total, stage = stage) }
                    }
                    update(item.id) { it.copy(status = DownloadStatus.DONE, stage = null) }
                    AppLog.i("✔ Done: ${item.title}")
                } catch (e: CancellationException) {
                    AppLog.i("Stopped: ${item.title}")
                    throw e
                } catch (e: Exception) {
                    AppLog.e("Failed: ${item.title}", e)
                    update(item.id) {
                        it.copy(status = DownloadStatus.FAILED, stage = null, error = e.message ?: e.toString())
                    }
                }
            }
            current = item.id to job
            job.join()
            current = null
            // Cancelled by pause: back to the queue, segments are kept.
            if (job.isCancelled) update(item.id) { it.copy(status = DownloadStatus.QUEUED, stage = null) }
            if (_items.value.none { it.id == item.id }) tempDir(item.id).deleteRecursively()
            save()
        }
    }

    private fun nextItem(): DownloadItem? = synchronized(lock) {
        val next = if (_paused.value) null else _items.value.firstOrNull { it.status == DownloadStatus.QUEUED }
        if (next == null) worker = null
        next
    }

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun load() {
        try {
            if (!queueFile.exists()) return
            val json = JSONObject(queueFile.readText())
            val array = json.getJSONArray("items")
            _items.value = (0 until array.length()).map { DownloadItem.fromJson(array.getJSONObject(it)) }
            _paused.value = json.optBoolean("paused")
            AppLog.i("Queue loaded: ${_items.value.size} items" + if (_paused.value) ", paused" else "")
        } catch (e: Exception) {
            AppLog.e("Can't read the saved queue, starting with an empty one", e)
        }
    }

    private fun save() {
        synchronized(lock) {
            val json = JSONObject()
                .put("paused", _paused.value)
                .put("items", JSONArray(_items.value.map { it.toJson() }))
            val tmp = File(queueFile.path + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(queueFile)
        }
    }
}
