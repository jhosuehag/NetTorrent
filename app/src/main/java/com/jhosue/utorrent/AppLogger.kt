package com.jhosue.utorrent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app singleton logger that mirrors engine events to an observable buffer.
 *
 * Usage:
 *   AppLogger.d("TAG", "message")   — debug
 *   AppLogger.e("TAG", "message")   — error
 *   AppLogger.i("TAG", "message")   — info
 *   AppLogger.w("TAG", "message")   — warning
 *
 * The buffer holds the last [MAX_LINES] entries and is exposed as [logsFlow].
 * The UI observes this flow to display and copy logs without polling.
 */
object AppLogger {

    private const val MAX_LINES = 500
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** Circular buffer — protected by its own lock. */
    private val buffer = ArrayDeque<String>(MAX_LINES + 1)

    /** StateFlow emitting a snapshot of [buffer] on every new entry. */
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    // ── Public logging API ─────────────────────────────────────────────────────

    fun d(tag: String, message: String) = append("D", tag, message).also { Log.d(tag, message) }
    fun i(tag: String, message: String) = append("I", tag, message).also { Log.i(tag, message) }
    fun w(tag: String, message: String) = append("W", tag, message).also { Log.w(tag, message) }
    fun e(tag: String, message: String) = append("E", tag, message).also { Log.e(tag, message) }
    fun e(tag: String, message: String, throwable: Throwable) =
        append("E", tag, "$message — ${throwable.message}").also { Log.e(tag, message, throwable) }

    /** Returns the full log as a single newline-delimited string (for clipboard). */
    fun snapshot(): String = synchronized(buffer) { buffer.joinToString("\n") }

    /** Wipes the buffer (e.g., on user request). */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _logsFlow.tryEmit(emptyList())
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun append(level: String, tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$level/$tag] $message"
        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
            // Emit an immutable snapshot so collectors don't share mutable state
            _logsFlow.tryEmit(buffer.toList())
        }
    }
}
