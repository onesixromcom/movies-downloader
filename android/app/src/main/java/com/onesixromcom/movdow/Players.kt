package com.onesixromcom.movdow

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** What a player page (ashdi.vip, zetvideo.net, tortuga.tw) has: one video or a playlist. */
sealed interface PlayerContent

data class SingleVideo(val file: String, val subtitles: String?) : PlayerContent

/** [path]: titles from the top of the playlist, e.g. [voice, season, episode]. */
data class PlaylistItem(val path: List<String>, val file: String, val subtitles: String?) {
    /** Identifies the episode in the player, to find its fresh link when the download starts. */
    val key: String get() = path.joinToString("|")
}

data class Playlist(val items: List<PlaylistItem>) : PlayerContent

/**
 * Port of the player parts of movie.sh, scripts/ashdi.js and scripts/tortuga_decode.js.
 * Players use PlayerJS: `file:'https://…m3u8'` for a video or `file:'[{"title":…,"folder":[…]}]'` for a playlist;
 * tortuga.tw encodes the value.
 */
object Players {

    private val FILE_START = Regex("""\bfile\s*:\s*(['"])""")
    private val SUBTITLE = Regex("""\bsubtitle\s*:\s*['"]([^'"]*)['"]""")

    fun parse(html: String): PlayerContent? {
        val subtitles = SUBTITLE.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        for (value in fileValues(html)) {
            parseValue(value.trim(), subtitles, decoded = false)?.let { return it }
        }
        return null
    }

    /**
     * Values of `file:'…'` / `file:"…"` JS strings. Scanned by hand: a regex for escaped strings
     * overflows the stack on long playlists.
     */
    private fun fileValues(html: String): List<String> {
        val values = mutableListOf<String>()
        var from = 0
        while (true) {
            val match = FILE_START.find(html, from) ?: break
            val quote = match.groupValues[1][0]
            val value = StringBuilder()
            var i = match.range.last + 1
            while (i < html.length && html[i] != quote) {
                if (html[i] == '\\' && i + 1 < html.length) {
                    val next = html[i + 1]
                    // JS string escapes of quotes/slash; keep the rest (\uXXXX, \\) for the JSON parser.
                    if (next == '\'' || next == '"' || next == '/') value.append(next) else value.append('\\').append(next)
                    i += 2
                } else {
                    value.append(html[i])
                    i++
                }
            }
            values += value.toString()
            from = i + 1
        }
        return values
    }

    private fun parseValue(value: String, subtitles: String?, decoded: Boolean): PlayerContent? = when {
        value.startsWith("[") -> try {
            Playlist(playlistItems(JSONArray(value), emptyList())).takeIf { it.items.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
        value.startsWith("http") -> SingleVideo(value, subtitles)
        value.startsWith("//") -> SingleVideo("https:$value", subtitles)
        !decoded -> tortugaDecode(value)?.let { parseValue(it.trim(), subtitles, decoded = true) }
        else -> null
    }

    private fun playlistItems(array: JSONArray, path: List<String>): List<PlaylistItem> {
        val items = mutableListOf<PlaylistItem>()
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i) ?: continue
            val title = node.optString("title").trim().ifEmpty { "${i + 1}" }
            val folder = node.optJSONArray("folder")
            when {
                folder != null -> items += playlistItems(folder, path + title)
                node.has("file") -> items += PlaylistItem(
                    path + title,
                    node.getString("file").let { if (it.startsWith("//")) "https:$it" else it },
                    node.optString("subtitle").takeIf { it.isNotBlank() },
                )
            }
        }
        return items
    }

    /**
     * scripts/tortuga_decode.js: drop the trailing "==", base64-decode, XOR with a keystream
     * seeded by the first byte.
     */
    fun tortugaDecode(input: String): String? {
        if (input.length < 4) return null
        val stripped = input.dropLast(2)
        val bytes = try {
            Base64.getDecoder().decode(stripped + "=".repeat((4 - stripped.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size < 2) return null
        val key = bytes[0].toInt() and 0xff
        val out = ByteArray(bytes.size - 1) { i ->
            ((bytes[i + 1].toInt() and 0xff) xor ((key + 7 * i + 13) % 256)).toByte()
        }
        return String(out, Charsets.UTF_8).takeIf { it.startsWith("[") || it.startsWith("http") }
    }

    /** "[Ukrainian]https://….vtt,[English]https://….vtt" */
    fun subtitles(spec: String?): List<Subtitle> {
        if (spec.isNullOrBlank()) return emptyList()
        return Regex("""\[([^\]]*)]([^,\[]+)""").findAll(spec)
            .map { Subtitle(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .filter { it.url.startsWith("http") }
            .toList()
    }

    /** For JSONObject-based data where a list can be an array or an object with numeric keys. */
    fun children(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> value.keys().asSequence().mapNotNull { value.optJSONObject(it) }.toList()
        else -> emptyList()
    }
}
