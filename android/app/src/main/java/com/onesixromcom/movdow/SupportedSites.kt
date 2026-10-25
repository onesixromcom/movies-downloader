package com.onesixromcom.movdow

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Site(
    val name: String,
    val url: String,
    val description: String,
    val extractor: (BrowserFetcher, (String) -> Unit) -> Extractor,
)

/** Websites the app can download from. */
object SupportedSites {

    val list = listOf(
        Site("uakino.best", "https://uakino.best/", "Movies, series, cartoons and anime", ::UakinoExtractor),
        Site("kinoukr.tv", "https://kinoukr.tv/", "Movies and series", ::KinoukrExtractor),
        Site("uafix.net", "https://uafix.net/", "Movies and series (UAFLIX)", ::UafixExtractor),
        Site("uaserials.com", "https://uaserials.com/", "Series and cartoons", ::UaserialsExtractor),
    )

    fun find(url: String): Site? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return list.firstOrNull { host == it.name || host.endsWith(".${it.name}") }
    }

    fun supports(url: String): Boolean = find(url) != null
}
