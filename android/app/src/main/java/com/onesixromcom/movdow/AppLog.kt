package com.onesixromcom.movdow

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Global log shown at the bottom of every tab (and in logcat with the "MovDow" tag). */
object AppLog {

    private const val TAG = "MovDow"
    private const val MAX_LINES = 1000

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(message: String) {
        Log.i(TAG, message)
        add(message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        add("ERROR: $message" + (error?.let { ": ${it.message ?: it}" } ?: ""))
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun add(message: String) {
        val line = synchronized(time) { time.format(Date()) } + " " + message
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}
