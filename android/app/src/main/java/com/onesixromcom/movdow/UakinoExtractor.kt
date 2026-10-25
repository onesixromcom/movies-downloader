package com.onesixromcom.movdow

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Port of providers/uakino.best.sh: page url -> voices -> episode players. */
class UakinoExtractor(browser: BrowserFetcher, log: (String) -> Unit = {}) : BaseExtractor(browser, log) {

    override suspend fun getPage(pageUrl: String): PageInfo {
        val html = browser.loadPage(pageUrl) { it.contains("data-news_id") || it.contains("full-text") }
        val doc = Jsoup.parse(html, pageUrl)
        val title = pageTitle(doc)
        val origin = siteOrigin(pageUrl)

        // uakino_get_list_id_locally / uakino_get_timestamp_locally
        val newsId = Regex("""data-news_id="([^"]+)"""").find(html)?.groupValues?.get(1)
        val time = Regex("""dle_edittime\s*=\s*'?(\d+)""").find(html)?.groupValues?.get(1)
        step("Page loaded: \"$title\", news_id=$newsId. Getting playlists…")

        if (newsId != null) {
            val url = origin + "engine/ajax/playlists.php?news_id=$newsId&xfield=playlist" +
                (time?.let { "&time=$it" } ?: "")
            val body = browser.fetch(
                url,
                mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            )
            val page = parsePlaylists(JSONObject(body).optString("response"), title, origin)
            if (page != null) return page.copy(url = pageUrl)
            step("Playlists response is empty")
        }

        // uakino_get_single_iframe_video_url: the player can still be a playlist (series).
        val iframe = doc.selectFirst("div.box.full-text.visible iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            step("Using the player on the page: $iframe")
            val src = iframe.replace("geoblock=ua", "").let { if (it.startsWith("//")) "https:$it" else it }
            return expandPlayers(listOf(PlayerTab("Default", src)), title, origin).copy(url = pageUrl)
        }
        return PageInfo(pageUrl, title, isSeries = false, voices = emptyList())
    }

    private fun pageTitle(doc: Document): String =
        doc.selectFirst("h1 span.solototle")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()

    companion object {
        // uakino_get_json_list
        fun parsePlaylists(response: String, title: String, origin: String): PageInfo? {
            if (response.isBlank()) return null
            val doc = Jsoup.parse(response)

            // Series flow: voice tabs, episodes are linked to a tab by data-id.
            val tabs = doc.select("div.playlists-lists div.playlists-items li[data-id]")
            if (tabs.isNotEmpty()) {
                val episodes = doc.select("div.playlists-videos li[data-file]")
                val voices = tabs.map { tab ->
                    val id = tab.attr("data-id")
                    Voice(
                        tab.text(),
                        episodes.filter { it.attr("data-id") == id }.map { Episode(it.text(), it.attr("data-file"), origin) },
                    )
                }.filter { it.episodes.isNotEmpty() }
                return PageInfo("", title, isSeries = true, voices = voices).takeIf { voices.isNotEmpty() }
            }

            // Voices flow: a single movie, every item is a voice.
            val voices = doc.select("li[data-file]").map { Voice(it.text(), listOf(Episode(title, it.attr("data-file"), origin))) }
            return PageInfo("", title, isSeries = false, voices = voices).takeIf { voices.isNotEmpty() }
        }
    }
}
