package com.kashif.ocrPlugin

/**
 * Lets through at most [maxFps] frames a second, judged by each frame's own timestamp, so live OCR
 * never runs a recognizer at camera frame rate. It counts attempts, not results, and the caller asks
 * it before reading the frame, so a scene with no text costs the same as one with text.
 *
 * Not thread-safe: each live recognizer calls it from its one analysis thread or serial queue.
 */
internal class FrameRateGate(maxFps: Int) {
    init {
        require(maxFps > 0) { "maxFps must be positive" }
    }

    private val intervalNanos = NANOS_PER_SECOND / maxFps
    private var lastPassNanos = Long.MIN_VALUE

    /** Returns true when a frame stamped [timestampNanos] should be read, and counts it as read. */
    fun tryPass(timestampNanos: Long): Boolean {
        if (lastPassNanos != Long.MIN_VALUE && timestampNanos - lastPassNanos < intervalNanos) return false
        lastPassNanos = timestampNanos
        return true
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
