package com.onesixromcom.movdow

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    /** SAF tree uri of the download folder. */
    val folderUri: String? = null,
    /** Episodes of a series go into a subfolder named after the show. Movies stay in the main folder. */
    val seriesSubfolder: Boolean = true,
    /** With [seriesSubfolder]: file names without the show name ("Season 1 Episode 1.mp4"). */
    val episodeNameOnly: Boolean = false,
    val autoFirstVoice: Boolean = false,
    val quality: String = "720",
    val subtitles: Boolean = true,
    /** Re-encode audio to AAC with gaps filled (ffmpeg aresample=async=1). */
    val fixAudio: Boolean = true,
    /** File and folder names in Latin letters, lowercase, "_" instead of spaces. */
    val transliterate: Boolean = true,
)

class SettingsRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    fun update(transform: (AppSettings) -> AppSettings) {
        val settings = transform(_state.value)
        prefs.edit()
            .putString("folderUri", settings.folderUri)
            .putBoolean("seriesSubfolder", settings.seriesSubfolder)
            .putBoolean("episodeNameOnly", settings.episodeNameOnly)
            .putBoolean("autoFirstVoice", settings.autoFirstVoice)
            .putString("quality", settings.quality)
            .putBoolean("subtitles", settings.subtitles)
            .putBoolean("fixAudio", settings.fixAudio)
            .putBoolean("transliterate", settings.transliterate)
            .apply()
        _state.value = settings
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            folderUri = prefs.getString("folderUri", defaults.folderUri),
            seriesSubfolder = prefs.getBoolean("seriesSubfolder", defaults.seriesSubfolder),
            episodeNameOnly = prefs.getBoolean("episodeNameOnly", defaults.episodeNameOnly),
            autoFirstVoice = prefs.getBoolean("autoFirstVoice", defaults.autoFirstVoice),
            quality = prefs.getString("quality", defaults.quality) ?: defaults.quality,
            subtitles = prefs.getBoolean("subtitles", defaults.subtitles),
            fixAudio = prefs.getBoolean("fixAudio", defaults.fixAudio),
            transliterate = prefs.getBoolean("transliterate", defaults.transliterate),
        )
    }
}
