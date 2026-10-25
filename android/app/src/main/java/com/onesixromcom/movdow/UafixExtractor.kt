package com.onesixromcom.movdow

import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of providers/uafix.net.sh.
 */
class UafixExtractor(browser: BrowserFetcher, log: (String) -> Unit = {}) : BaseExtractor(browser, log) {

    data class EpisodeLink(val title: String, val url: String)

    override suspend fun getPage(pageUrl: String): PageInfo {
        val html = browser.loadPage(pageUrl) { it.contains("ftitle") }
        val doc = Jsoup.parse(html, pageUrl)
        val title = title(doc)
        val origin = siteOrigin(pageUrl)
        val links = episodes(doc)

        if (links.isEmpty()) {
            val tabs = parseTabs(doc)
            step("Page loaded: \"$title\", players: ${tabs.joinToString { it.name }}. Opening players…")
            return expandPlayers(tabs, title, origin).copy(url = pageUrl)
        }

        step("Page loaded: \"$title\", ${links.size} episodes. Getting players…")
        val voices = LinkedHashMap<String, MutableList<Episode>>()
        links.forEachIndexed { i, link ->
            step("Episode ${i + 1} / ${links.size}: ${link.title}")
            try {
                // fetch() inside the WebView: same cookies as the page (Cloudflare), no navigation.
                val tabs = parseTabs(Jsoup.parse(browser.fetch(link.url), link.url))
                if (tabs.isEmpty()) AppLog.e("No player on ${link.url}")
                tabs.forEach { voices.getOrPut(it.name) { mutableListOf() } += Episode(link.title, it.url, origin) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Episode ${link.title}: ${link.url}", e)
            }
        }
        return PageInfo(pageUrl, title, isSeries = true, voices = voices.map { (name, episodes) -> Voice(name, episodes) })
    }

    companion object {
        private val EPISODE_NUMBER = Regex("""season-(\d+)-episode-(\d+)""")

        fun title(doc: Document): String =
            doc.selectFirst("h1#ftitle [itemprop=name]")?.text()?.trim()?.ifEmpty { null }
                ?: doc.selectFirst("h1")?.text()
                    ?.replace(Regex("""\s+(дивитис[ья]|дивитися)\s+онлайн.*$""", RegexOption.IGNORE_CASE), "")
                    ?.trim()?.ifEmpty { null }
                ?: doc.title()

        /** Episodes of the season page, in watching order (the site lists the newest first). */
        fun episodes(doc: Document): List<EpisodeLink> {
            val links = doc.select("#sers-wr a.vi-img").map { a ->
                val title = a.selectFirst(".vi-title")?.text()?.trim()?.ifEmpty { null } ?: a.attr("href")
                EpisodeLink(title, a.absUrl("href").ifEmpty { a.attr("href") })
            }
            val numbers = links.map { link ->
                EPISODE_NUMBER.find(link.url)?.let { it.groupValues[1].toInt() * 10_000 + it.groupValues[2].toInt() }
            }
            return if (numbers.all { it != null }) links.zip(numbers).sortedBy { it.second }.map { it.first } else links
        }
    }
}
