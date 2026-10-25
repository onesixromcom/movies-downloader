package com.onesixromcom.movdow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class Subtitle(val name: String, val url: String)

data class ResolvedPlaylist(
    val playerUrl: String,
    val masterUrl: String,
    val playlistUrl: String,
    val quality: String?,
    val segments: List<String>,
    val subtitles: List<Subtitle>,
    /** Sum of #EXTINF durations. */
    val durationUs: Long,
)

/**
 * Port of movie_get_main_playlist + movie_get_quality_playlist + movie_get_subtitles from movie.sh:
 * player page -> master m3u8 -> playlist of the chosen quality -> segments.
 */
class PlaylistResolver(private val client: OkHttpClient, private val userAgent: String) {

    companion object {
        val QUALITIES = listOf("480", "720", "1080")

        private val EXTINF_REGEX = Regex("""#EXTINF:\s*([\d.]+)""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
        private val HLS_QUALITY_REGEX = Regex("""/hls/(\d{3,4})/""")

        /** Variant of a master playlist with its quality (height, or the 16:9 height for wide videos). */
        data class Variant(val url: String, val quality: Int?)

        fun variants(baseUrl: String, body: String): List<Variant> {
            val base = baseUrl.toHttpUrl()
            val variants = mutableListOf<Variant>()
            var resolution: Int? = null
            for (raw in body.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-STREAM-INF") -> resolution = RESOLUTION_REGEX.find(line)?.let {
                        // 1920x800 is "1080p", 854x480 is "480p".
                        maxOf(it.groupValues[2].toInt(), it.groupValues[1].toInt() * 9 / 16)
                    }
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        val url = base.resolve(line)?.toString() ?: line
                        val quality = resolution ?: HLS_QUALITY_REGEX.find(url)?.groupValues?.get(1)?.toInt()
                        variants += Variant(url, quality)
                        resolution = null
                    }
                }
            }
            return variants
        }

        /** The best variant not above [quality] (like movie.sh: requested, then lower); the lowest if all are higher. */
        fun pickVariant(variants: List<Variant>, quality: String): Variant? {
            val target = quality.toInt() + 20
            val known = variants.filter { it.quality != null }
            if (known.isEmpty()) return variants.firstOrNull()
            return known.filter { it.quality!! <= target }.maxByOrNull { it.quality!! }
                ?: known.minByOrNull { it.quality!! }
        }
    }

    /**
     * [url] is a player page or an m3u8; [key] picks the episode when the player has a playlist.
     * [referer] is sent when fetching the player page.
     */
    suspend fun resolve(url: String, key: String?, referer: String, quality: String): ResolvedPlaylist =
        withContext(Dispatchers.IO) {
            val player = if (url.startsWith("//")) "https:$url" else url
            val master: String
            val subtitleSpec: String?
            val mediaReferer: String
            if (player.contains(".m3u8")) {
                master = player
                subtitleSpec = null
                mediaReferer = referer
            } else {
                AppLog.i("Player: $player")
                val content = Players.parse(get(player, referer)) ?: throw IOException("No video found in the player")
                val video = when (content) {
                    is SingleVideo -> content.file to content.subtitles
                    is Playlist -> {
                        val item = key?.let { content.items.firstOrNull { item -> item.key == key } }
                            ?: throw IOException(
                                if (key == null) "The player has a playlist of ${content.items.size} videos, process the page again"
                                else "\"${key.replace("|", " / ")}\" is not in the player anymore",
                            )
                        item.file to item.subtitles
                    }
                }
                master = video.first
                subtitleSpec = video.second
                mediaReferer = player
            }
            AppLog.i("Master playlist: $master")

            val masterBody = get(master, mediaReferer)
            val playlistUrl: String
            val picked: String?
            if (masterBody.contains("#EXTINF")) {
                playlistUrl = master
                picked = null
            } else {
                val variants = variants(master, masterBody)
                val variant = pickVariant(variants, quality) ?: throw IOException("No variants in the master playlist")
                playlistUrl = variant.url
                picked = variant.quality?.toString()
                AppLog.i(
                    "Qualities: " + variants.joinToString { it.quality?.let { q -> "${q}p" } ?: "?" } +
                        ", using ${picked?.let { "${it}p" } ?: "the first one"}",
                )
            }
            AppLog.i("Playlist: $playlistUrl")
            val body = if (playlistUrl == master) masterBody else get(playlistUrl, mediaReferer)
            if (body.contains("#EXT-X-KEY")) throw IOException("Encrypted playlists are not supported yet")

            val base = playlistUrl.toHttpUrl()
            val segments = body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { base.resolve(it)?.toString() ?: it }
                .toList()
            val subtitles = Players.subtitles(subtitleSpec)
            AppLog.i(
                "Segments: ${segments.size}" +
                    (if (subtitles.isNotEmpty()) ", subtitles: " + subtitles.joinToString { it.name } else ""),
            )
            val durationUs = EXTINF_REGEX.findAll(body).sumOf { (it.groupValues[1].toDouble() * 1_000_000).toLong() }
            ResolvedPlaylist(mediaReferer, master, playlistUrl, picked, segments, subtitles, durationUs)
        }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }
}
