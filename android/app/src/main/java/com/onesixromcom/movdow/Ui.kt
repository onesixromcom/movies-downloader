package com.onesixromcom.movdow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

const val TAB_ADD = 0
const val TAB_QUEUE = 1
const val TAB_SETTINGS = 2
const val TAB_ABOUT = 3

const val HOME_PAGE = "https://github.com/onesixromcom/movies-downloader"
const val WEBSITE_PAGE = "https://16rom.com"

/** State of the Add tab: the processed page and the user's choice. */
class AddState {
    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var page by mutableStateOf<PageInfo?>(null)
    var voiceIndex by mutableStateOf<Int?>(null)
        private set
    var selected by mutableStateOf(setOf<Int>())
    var quality by mutableStateOf("720")

    val voice: Voice? get() = voiceIndex?.let { page?.voices?.getOrNull(it) }

    /** All episodes of the voice are selected by default. */
    fun selectVoice(index: Int?) {
        voiceIndex = index
        selected = voice?.episodes?.indices?.toSet() ?: emptySet()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun MainScreen(
    tab: Int,
    onTab: (Int) -> Unit,
    addState: AddState,
    onProcess: () -> Unit,
    onAddToQueue: () -> Unit,
    settingsRepository: SettingsRepository,
    webView: WebView,
) {
    val context = LocalContext.current
    val items by DownloadManager.items.collectAsState()
    val paused by DownloadManager.paused.collectAsState()
    val settings by settingsRepository.state.collectAsState()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settingsRepository.update { it.copy(folderUri = uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                TabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
                    val active = items.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
                    Tab(selected = tab == TAB_ADD, onClick = { onTab(TAB_ADD) }, text = { Text("Add") })
                    Tab(
                        selected = tab == TAB_QUEUE,
                        onClick = { onTab(TAB_QUEUE) },
                        text = { Text(if (active > 0) "Queue ($active)" else "Queue") },
                    )
                    Tab(selected = tab == TAB_SETTINGS, onClick = { onTab(TAB_SETTINGS) }, text = { Text("Settings") })
                    Tab(selected = tab == TAB_ABOUT, onClick = { onTab(TAB_ABOUT) }, text = { Text("About") })
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    TAB_ADD -> AddTab(
                        state = addState,
                        onProcess = onProcess,
                        onAddToQueue = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            onAddToQueue()
                        },
                    )
                    TAB_QUEUE -> QueueTab(items, paused)
                    TAB_SETTINGS -> SettingsTab(
                        settings = settings,
                        onChange = settingsRepository::update,
                        onChooseFolder = { folderPicker.launch(settings.folderUri?.let(Uri::parse)) },
                    )
                    else -> AboutTab()
                }
            }
            LogPanel()
            // Always in the tree (but never shown): the WebView must survive tab switches.
            // Hidden with GONE: a visible WebView of 0 height stops the rest of the screen from drawing.
            AndroidView(
                factory = { webView.apply { visibility = View.GONE } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AddTab(
    state: AddState,
    onProcess: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.url,
            onValueChange = { state.url = it },
            label = { Text("Page URL") },
            placeholder = { Text("Movie page link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onProcess() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onProcess, enabled = !state.loading) { Text("Process") }
            if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val page = state.page
        if (page != null) {
            Text(page.title, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    label = "Voice",
                    options = page.voices.map { if (page.isSeries) "${it.title} (${it.episodes.size})" else it.title },
                    selected = state.voiceIndex,
                    placeholder = "Choose voice",
                    onSelect = state::selectVoice,
                    modifier = Modifier.weight(1f),
                )
                Picker(
                    label = "Quality",
                    options = PlaylistResolver.QUALITIES.map { "${it}p" },
                    selected = PlaylistResolver.QUALITIES.indexOf(state.quality),
                    onSelect = { state.quality = PlaylistResolver.QUALITIES[it] },
                    // Fixed width: Row measures it before the weighted voice picker, and the button fills
                    // the width it gets, which left 0 for the voice picker.
                    modifier = Modifier.width(120.dp),
                )
            }

            val voice = state.voice
            Button(
                onClick = onAddToQueue,
                enabled = voice != null && state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        voice == null -> "Choose a voice"
                        page.isSeries -> "Add ${state.selected.size} to queue"
                        else -> "Add to queue"
                    },
                )
            }

            if (voice != null && page.isSeries) {
                val all = voice.episodes.indices.toSet()
                val toggleAll = { state.selected = if (state.selected.size == all.size) emptySet() else all }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggleAll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        state = when (state.selected.size) {
                            0 -> ToggleableState.Off
                            all.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = toggleAll,
                    )
                    Text("All (${state.selected.size} / ${all.size})", style = MaterialTheme.typography.titleSmall)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(voice.episodes) { index, episode ->
                        val toggle = {
                            state.selected = if (index in state.selected) state.selected - index else state.selected + index
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = index in state.selected, onCheckedChange = { toggle() })
                            Text(episode.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTab(items: List<DownloadItem>, paused: Boolean) {
    // Text of the confirmation and the action to run.
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val hasWork = items.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { DownloadManager.setPaused(!paused) }, enabled = hasWork || paused) {
                Text(if (paused) "Resume" else "Pause")
            }
            OutlinedButton(
                onClick = { confirm = "Remove all ${items.size} items from the queue?" to DownloadManager::removeAll },
                enabled = items.isNotEmpty(),
            ) { Text("Remove all") }
        }
        if (paused) Text("Downloads are paused.", color = MaterialTheme.colorScheme.primary)
        if (items.isEmpty()) {
            Text(
                "The queue is empty. Share a page from the browser or use the Add tab.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                QueueRow(
                    item = item,
                    onRemove = { confirm = "Remove \"${item.title}\" from the queue?" to { DownloadManager.remove(item.id) } },
                    onRetry = { DownloadManager.retry(item.id) },
                )
            }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove") },
            text = { Text("$text\nSaved videos stay in the folder, unfinished downloads are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    action()
                    confirm = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QueueRow(item: DownloadItem, onRemove: () -> Unit, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(item.voice.takeIf { it.isNotBlank() }, "${item.quality}p", item.subfolder?.let { "folder: $it" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val percent = if (item.total > 0) item.done * 100 / item.total else 0
            Text(
                when (item.status) {
                    DownloadStatus.QUEUED -> if (item.done > 0) "Queued, $percent% downloaded" else "Queued"
                    DownloadStatus.DOWNLOADING -> item.stage ?: "Downloading: ${item.done} / ${item.total} segments ($percent%)"
                    DownloadStatus.DONE -> "Done"
                    DownloadStatus.FAILED -> "Failed: ${item.error}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (item.status) {
                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (item.status == DownloadStatus.DOWNLOADING || (item.status == DownloadStatus.QUEUED && item.done > 0)) {
                LinearProgressIndicator(
                    progress = { if (item.total > 0) item.done.toFloat() / item.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.status == DownloadStatus.FAILED) TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onChooseFolder: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Download folder", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                settings.folderUri?.let(::folderLabel) ?: "Not selected",
                modifier = Modifier.weight(1f),
                color = if (settings.folderUri == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onChooseFolder) { Text("Choose") }
        }
        HorizontalDivider()
        SwitchRow(
            title = "Folder per series",
            description = "Episodes go into a folder named after the show. Movies are saved into the download folder.",
            checked = settings.seriesSubfolder,
            onChange = { value -> onChange { it.copy(seriesSubfolder = value) } },
        )
        SwitchRow(
            title = "Episode names without the show name",
            description = "In the series folder the show name is not repeated: " +
                "\"Show/Season 1 Episode 1.mp4\" instead of \"Show/Show - Season 1 Episode 1.mp4\".",
            checked = settings.episodeNameOnly,
            enabled = settings.seriesSubfolder,
            onChange = { value -> onChange { it.copy(episodeNameOnly = value) } },
        )
        SwitchRow(
            title = "Transliterate file names",
            description = "Ukrainian letters to Latin (official transliteration), lowercase, \"_\" instead of spaces, for folders too. " +
                "\"The Perfect Sadness\" -> the_perfect_sadness.mp4",
            checked = settings.transliterate,
            onChange = { value -> onChange { it.copy(transliterate = value) } },
        )
        SwitchRow(
            title = "Select the first voice",
            description = "Don't ask which voice to use, take the first one.",
            checked = settings.autoFirstVoice,
            onChange = { value -> onChange { it.copy(autoFirstVoice = value) } },
        )
        SwitchRow(
            title = "Download subtitles",
            description = "Saved as .srt next to the video when the player has them.",
            checked = settings.subtitles,
            onChange = { value -> onChange { it.copy(subtitles = value) } },
        )
        SwitchRow(
            title = "Fix audio",
            description = "Re-encode audio to AAC 128 kbps, filling gaps between segments, so sound stays in sync " +
                "(like ffmpeg aresample=async=1). Video is copied. Adds a few minutes per movie.",
            checked = settings.fixAudio,
            onChange = { value -> onChange { it.copy(fixAudio = value) } },
        )
        HorizontalDivider()
        Text("Default quality", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaylistResolver.QUALITIES.forEach { quality ->
                FilterChip(
                    selected = settings.quality == quality,
                    onClick = { onChange { it.copy(quality = quality) } },
                    label = { Text("${quality}p") },
                )
            }
        }
        Text(
            "If the quality is not available, the next lower one is used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutTab() {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        version?.let { Text("Version $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        UpdateCheck(version, open)
        Text(
            "Downloads movies and series from free streaming websites to watch them later without ads, " +
                "for example on a TV from a USB drive.",
        )
        Text(
            "Share a movie page from the browser to this app, or paste its link on the Add tab.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Home page", style = MaterialTheme.typography.titleMedium)
        Link(HOME_PAGE, onClick = { open(HOME_PAGE) })
        Text("Website", style = MaterialTheme.typography.titleMedium)
        Link(WEBSITE_PAGE, onClick = { open(WEBSITE_PAGE)})

        HorizontalDivider()
        Text("Supported websites", style = MaterialTheme.typography.titleMedium)
        SupportedSites.list.forEach { site ->
            Column {
                Link(site.name, onClick = { open(site.url) })
                Text(site.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Failed(val error: String) : UpdateState
}

@Composable
private fun UpdateCheck(version: String?, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            enabled = state != UpdateState.Checking,
            onClick = {
                state = UpdateState.Checking
                scope.launch {
                    state = try {
                        val release = UpdateChecker.latest()
                        if (UpdateChecker.isNewer(release.version, version ?: "0")) UpdateState.Available(release)
                        else UpdateState.UpToDate
                    } catch (e: Exception) {
                        AppLog.i("Update check failed: $e")
                        UpdateState.Failed(e.message ?: e.toString())
                    }
                }
            },
        ) { Text("Check for updates") }
        if (state == UpdateState.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    when (val s = state) {
        UpdateState.UpToDate -> Text("You have the latest version.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("New version ${s.release.version} is available.")
            s.release.apkUrl?.let { apk -> Link("Download apk", onClick = { open(apk) }) }
            Link("Release notes", onClick = { open(s.release.pageUrl) })
        }
        is UpdateState.Failed -> Text("Can't check for updates: ${s.error}", color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Picker(
    label: String,
    options: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(
                    selected?.let { options.getOrNull(it) } ?: placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(" ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    onSelect(index)
                })
            }
        }
    }
}

@Composable
private fun LogPanel() {
    val context = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Follow new lines.
    LaunchedEffect(lines.size, expanded) {
        if (expanded && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (expanded) "▾ Log" else "▸ Log") + " (${lines.size})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("log", lines.joinToString("\n")))
                    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                TextButton(onClick = AppLog::clear) { Text("Clear") }
            }
            if (expanded) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(horizontal = 12.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = if (line.contains("ERROR:")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** content://…/tree/primary%3AMovies -> "Internal storage/Movies". */
private fun folderLabel(uri: String): String =
    try {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri)).replace(Regex("^primary:"), "Internal storage/")
    } catch (e: IllegalArgumentException) {
        uri
    }
