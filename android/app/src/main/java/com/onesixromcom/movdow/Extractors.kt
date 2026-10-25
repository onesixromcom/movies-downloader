package com.onesixromcom.movdow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * [url]: player page (or m3u8), [referer]: sent when fetching it,
 * [key]: the episode inside the player's playlist ([PlaylistItem.key]).
 */
data class Episode(val title: String, val url: String, val referer: String, val key: String? = null)

data class Voice(val title: String, val episodes: List<Episode>)

data class PageInfo(val url: String, val title: String, val isSeries: Boolean, val voices: List<Voice>)

/** A website: page url -> voices and episodes. One per supported site (see [SupportedSites]). */
interface Extractor {
    suspend fun getPage(pageUrl: String): PageInfo
}

/** Player tab on the page: "Online" -> https://ashdi.vip/serial/XXXX */
data class PlayerTab(val name: String, val url: String)

fun siteOrigin(url: String): String = url.toHttpUrl().let { "${it.scheme}://${it.host}/" }

abstract class BaseExtractor(
    protected val browser: BrowserFetcher,
    private val log: (String) -> Unit,
) : Extractor {

    /** Progress for the log and the Add tab status line. */
    protected fun step(message: String) {
        AppLog.i(message)
        log(message)
    }

    companion object {
        /**
         * DLE sites (kinoukr.tv, uafix.net): `div.tabs-sel` names match `div.tabs-b.video-box` iframes by order.
         * Trailers are skipped.
         */
        fun parseTabs(doc: Document): List<PlayerTab> {
            val names = doc.select("div.tabs-sel > span").map { it.text().trim() }
            return doc.select("div.tabs-b.video-box iframe").mapIndexedNotNull { i, iframe ->
                val src = iframe.absUrl("src").ifEmpty { iframe.attr("src") }
                val name = names.getOrNull(i)?.ifEmpty { null } ?: "Player ${i + 1}"
                PlayerTab(name, src).takeUnless {
                    src.isBlank() || src.contains("youtube.com") || name.contains("трейлер", ignoreCase = true)
                }
            }
        }
    }

    /**
     * Open every player tab: a single video becomes a voice with one video,
     * a playlist (ashdi/tortuga series) becomes voices with their episodes.
     */
    protected suspend fun expandPlayers(tabs: List<PlayerTab>, pageTitle: String, referer: String): PageInfo {
        val userAgent = browser.userAgent
        return withContext(Dispatchers.IO) {
            val voices = mutableListOf<Voice>()
            var isSeries = false
            for (tab in tabs) {
                try {
                    val content = Players.parse(get(tab.url, referer, userAgent))
                    when (content) {
                        is SingleVideo -> {
                            AppLog.i("${tab.name}: one video (${tab.url})")
                            voices += Voice(tab.name, listOf(Episode(pageTitle, tab.url, referer)))
                        }
                        is Playlist -> {
                            isSeries = true
                            val groups = content.items.groupBy { if (it.path.size > 1) it.path.first() else tab.name }
                            AppLog.i("${tab.name}: playlist, ${groups.size} voices, ${content.items.size} videos (${tab.url})")
                            groups.forEach { (voice, items) ->
                                voices += Voice(
                                    if (tabs.size > 1 && voice != tab.name) "${tab.name}: $voice" else voice,
                                    items.map {
                                        val title = if (it.path.size > 1) it.path.drop(1).joinToString(" ") else it.path.first()
                                        Episode(title, tab.url, referer, it.key)
                                    },
                                )
                            }
                        }
                        null -> AppLog.e("${tab.name}: no video found in ${tab.url}")
                    }
                } catch (e: IOException) {
                    AppLog.e("${tab.name}: can't open the player ${tab.url}", e)
                }
            }
            PageInfo("", pageTitle, isSeries, voices)
        }
    }

    private fun get(url: String, referer: String, userAgent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Referer", referer).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }
}
