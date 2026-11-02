package com.kashif.cameraK.utils

import kotlin.concurrent.Volatile

/**
 * Lightweight, opt-in logger for CameraK internals.
 *
 * Disabled by default — no diagnostics are emitted unless [enabled] is set to true. Apps that want
 * CameraK logs (e.g. in debug builds) opt in and optionally route them somewhere custom:
 *
 * ```kotlin
 * CameraKLogger.enabled = true
 * // optional: forward to your own logging stack
 * CameraKLogger.sink = { level, tag, message, throwable ->
 *     myLogger.log(level.name, "$tag: $message", throwable)
 * }
 * ```
 *
 * When [enabled] and no [sink] is set, logs are printed to the platform console.
 *
 * The rule for every CameraK module that logs through this object:
 * - A message names the operation and its outcome, never its subject: no recognised text, barcode value,
 *   file path, URI, asset identifier or image bytes, in a log message or in an exception message.
 * - Every catch that returns a fallback logs the operation and the throwable once, passing the throwable as
 *   the separate `throwable` argument rather than formatting it into the message.
 * - A platform throwable's own message may hold a path or URI, so a [sink] that forwards throwables off the
 *   device must scrub or drop that message.
 * - The tag is the published artifact's: `CameraK` for camerak-core and camerak, then `QRScanner`,
 *   `TextRecognition`, `ImageSaverPlugin` and `Analyzer` for the plugins.
 */
object CameraKLogger {
    enum class Level { DEBUG, WARN, ERROR }

    /** Master switch. When false (default) nothing is logged. */
    @Volatile
    var enabled: Boolean = false

    /** Optional custom sink. When null, [enabled] logs go to the platform console. */
    @Volatile
    var sink: ((level: Level, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (!enabled) return
        sink?.invoke(level, tag, message, throwable) ?: defaultSink(level, tag, message, throwable)
    }

    private fun defaultSink(level: Level, tag: String, message: String, throwable: Throwable?) {
        val trace = throwable?.let { "\n" + it.stackTraceToString() } ?: ""
        println("CameraK/${level.name} [$tag] $message$trace")
    }
}
