package com.onesixromcom.movdow

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port of providers/uaserials.com.sh and scripts/uaserials_com_crypto.js.
 */
class UaserialsExtractor(browser: BrowserFetcher, log: (String) -> Unit = {}) : BaseExtractor(browser, log) {

    override suspend fun getPage(pageUrl: String): PageInfo {
        val html = browser.loadPage(pageUrl) { it.contains("data-tag1") || it.contains("short-title") }
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst("h1.short-title")?.text()?.trim()?.ifEmpty { null } ?: doc.title()
        val origin = siteOrigin(pageUrl)
        val tag1 = doc.selectFirst("player-control[data-tag1]")?.attr("data-tag1")
            ?: throw IOException("Player data (data-tag1) not found on the page")

        val data = try {
            decrypt(tag1)
        } catch (e: Exception) {
            throw IOException("Can't decrypt the player data, the site may have changed the passphrase", e)
        }
        step("Page loaded: \"$title\", player tabs: " + Players.children(data).joinToString { it.optString("tabName") })

        // Tabs with a ready list of seasons.
        val seasons = seasonVoices(data, origin)
        if (seasons.isNotEmpty()) return PageInfo(pageUrl, title, isSeries = true, voices = seasons)

        // Tabs with a player url (single video or ashdi/tortuga playlist).
        val tabs = Players.children(data)
            .filter { it.has("url") && !it.optString("tabName").contains("трейлер", ignoreCase = true) }
            .map { PlayerTab(it.optString("tabName").ifEmpty { "Player" }, it.getString("url")) }
        step("Opening players…")
        return expandPlayers(tabs, title, origin).copy(url = pageUrl)
    }

    companion object {
        private const val PASSPHRASE = "297796CCB81D255125"

        /** CryptoJS: PBKDF2-SHA512 (999 iterations, 256-bit key) + AES-CBC. */
        fun decrypt(tag1: String, passphrase: String = PASSPHRASE): JSONArray {
            val json = JSONObject(tag1)
            val spec = PBEKeySpec(passphrase.toCharArray(), hex(json.getString("salt")), 999, 256)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(hex(json.getString("iv"))))
            val text = String(cipher.doFinal(Base64.getDecoder().decode(json.getString("ciphertext"))), Charsets.UTF_8)
            return try {
                JSONArray(text)
            } catch (e: Exception) {
                // Same cleanup as the JS version.
                JSONArray(text.replace(Regex("[\r\n]"), "").replace("\\", ""))
            }
        }

        /** Voices from `seasons[].episodes[].sounds[]` tabs (sound = voice). */
        fun seasonVoices(data: JSONArray, origin: String): List<Voice> {
            val voices = LinkedHashMap<String, MutableList<Episode>>()
            for (tab in Players.children(data)) {
                if (!tab.has("seasons")) continue
                Players.children(tab.opt("seasons")).forEachIndexed { s, season ->
                    val seasonTitle = season.optString("title").ifEmpty { "Season ${s + 1}" }
                    Players.children(season.opt("episodes")).forEachIndexed { e, episode ->
                        val episodeTitle = "$seasonTitle " + episode.optString("title").ifEmpty { "Episode ${e + 1}" }
                        Players.children(episode.opt("sounds")).forEachIndexed { v, sound ->
                            val url = sound.optString("url")
                            if (url.isNotBlank()) {
                                val voice = sound.optString("title").ifEmpty { "Voice ${v + 1}" }
                                voices.getOrPut(voice) { mutableListOf() } += Episode(episodeTitle, url, origin)
                            }
                        }
                    }
                }
            }
            return voices.map { (name, episodes) -> Voice(name, episodes) }
        }

        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
