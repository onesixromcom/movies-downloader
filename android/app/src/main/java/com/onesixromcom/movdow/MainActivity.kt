package com.onesixromcom.movdow

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var browser: BrowserFetcher
    private lateinit var settings: SettingsRepository

    private val addState = AddState()
    private var tab by mutableIntStateOf(TAB_ADD)
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme shows the splash while starting; replace it before the window is created.
        setTheme(R.style.Theme_MovDow)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = SettingsRepository.get(this)
        DownloadManager.init(this)
        webView = WebView(this)
        browser = BrowserFetcher(webView)
        addState.quality = settings.state.value.quality

        setContent {
            AppTheme {
                MainScreen(
                    tab = tab,
                    onTab = { tab = it },
                    addState = addState,
                    onProcess = { process(addState.url) },
                    onAddToQueue = ::addToQueue,
                    settingsRepository = settings,
                    webView = webView,
                )
            }
        }

        // Continue the queue left from the previous run.
        DownloadManager.start()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // "Share with..." from the browser.
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = extractUrl(text)
        if (url == null) {
            AppLog.e("Shared text has no URL: $text")
            return
        }
        AppLog.i("Shared from browser: $url")
        tab = TAB_ADD
        process(url)
    }

    // Browsers may share "Page title https://...".
    private fun extractUrl(text: String): String? = Regex("""https?://\S+""").find(text)?.value

    private fun process(input: String) {
        val url = extractUrl(input.trim())
        addState.page = null
        addState.selectVoice(null)
        if (url == null) {
            addState.message = "No URL."
            AppLog.e("No URL in \"$input\"")
            return
        }
        val site = SupportedSites.find(url)
        if (site == null) {
            addState.message = "Not supported. Websites: " + SupportedSites.list.joinToString { it.name }
            AppLog.e("Not supported: $url")
            return
        }
        addState.url = url
        addState.loading = true
        addState.message = "Loading page…"
        AppLog.i("Processing $url")

        job?.cancel()
        val newJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = site.extractor(browser) { addState.message = it }.getPage(url)
                if (page.voices.isEmpty()) {
                    addState.message = "No players found."
                    AppLog.e("No players found on the page")
                    return@launch
                }
                AppLog.i(
                    "Found ${if (page.isSeries) "series" else "movie"} \"${page.title}\", voices: " +
                        page.voices.joinToString { "${it.title} (${it.episodes.size})" },
                )
                val current = settings.state.value
                addState.page = page
                addState.quality = current.quality
                addState.selectVoice(if (page.voices.size == 1 || current.autoFirstVoice) 0 else null)
                addState.message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addState.message = "Error: ${e.message}"
                AppLog.e("Processing failed", e)
            } finally {
                if (job == coroutineContext[Job]) addState.loading = false
            }
        }
        job = newJob
        newJob.start()
    }

    private fun addToQueue() {
        val page = addState.page ?: return
        val voice = addState.voice ?: return
        val current = settings.state.value
        val folderUri = current.folderUri
        if (folderUri == null) {
            addState.message = "Choose the download folder in Settings first."
            AppLog.e("Download folder is not set")
            tab = TAB_SETTINGS
            return
        }

        val fileName = { name: String ->
            if (current.transliterate) Transliteration.fileName(name) else DownloadItem.safeName(name)
        }
        val subfolder = if (page.isSeries && current.seriesSubfolder) fileName(page.title) else null
        val items = addState.selected.sorted().map { index ->
            val episode = voice.episodes[index]
            val base = when {
                // The folder already has the show name.
                page.isSeries && subfolder != null && current.episodeNameOnly -> episode.title
                page.isSeries -> "${page.title} - ${episode.title}"
                else -> page.title
            }
            // Every voice is a separate file.
            val name = if (page.voices.size > 1) "$base (${voice.title})" else base
            DownloadItem(
                id = UUID.randomUUID().toString(),
                title = name,
                voice = voice.title,
                fileName = fileName(name),
                transliterate = current.transliterate,
                subfolder = subfolder,
                folderUri = folderUri,
                iframeUrl = episode.url,
                playerKey = episode.key,
                referer = episode.referer,
                quality = addState.quality,
                subtitles = current.subtitles,
                fixAudio = current.fixAudio,
            )
        }
        AppLog.i(
            "Added ${items.size} to the queue: voice ${voice.title}, ${addState.quality}p" +
                (subfolder?.let { ", folder \"$it\"" } ?: ""),
        )
        DownloadManager.add(items)
        addState.message = "Added ${items.size} to the queue."
        tab = TAB_QUEUE
    }
}
