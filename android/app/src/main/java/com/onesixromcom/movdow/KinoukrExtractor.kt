package com.onesixromcom.movdow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of providers/kinoukr.tv.sh.
 */
class KinoukrExtractor(browser: BrowserFetcher, log: (String) -> Unit = {}) : BaseExtractor(browser, log) {

    override suspend fun getPage(pageUrl: String): PageInfo {
        val html = browser.loadPage(pageUrl) { it.contains("video-box") || it.contains("fplayer") }
        val doc = Jsoup.parse(html, pageUrl)
        val title = title(doc)
        val tabs = parseTabs(doc)
        step("Page loaded: \"$title\", players: ${tabs.joinToString { it.name }}. Opening players…")
        return expandPlayers(tabs, title, siteOrigin(pageUrl)).copy(url = pageUrl)
    }

    companion object {
        fun title(doc: Document): String =
            doc.selectFirst("h1[itemprop=name]")?.text()?.trim()?.ifEmpty { null } ?: doc.title()
    }
}
