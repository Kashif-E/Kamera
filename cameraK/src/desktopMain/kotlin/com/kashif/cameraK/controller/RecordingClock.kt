package com.kashif.cameraK.controller

/**
 * Presentation time for a desktop recording: the capture time of each frame measured from the start
 * of the recording, with paused time taken out, so the video plays in real time and a pause leaves
 * no gap. Thread-safe: frames are stamped on the recording coroutine while pause and resume are
 * called from the app.
 */
internal class RecordingClock(private val startNanos: Long) {
    private var pausedSinceNanos: Long? = null
    private var pausedTotalNanos = 0L
    private var resumedAtNanos = startNanos
    private var lastTimestampMicros = -1L

    @Synchronized
    fun pause(nowNanos: Long) {
        if (pausedSinceNanos == null) pausedSinceNanos = nowNanos
    }

    @Synchronized
    fun resume(nowNanos: Long) {
        val since = pausedSinceNanos ?: return
        pausedTotalNanos += nowNanos - since
        pausedSinceNanos = null
        resumedAtNanos = nowNanos
    }

    /**
     * The presentation timestamp in microseconds for a frame captured at [captureNanos], or null if
     * the frame belongs to no recorded stretch: captured before the start, while paused or before the
     * last resume, or not after the previous frame (an encoder needs strictly increasing times).
     */
    @Synchronized
    fun timestampMicros(captureNanos: Long): Long? {
        if (pausedSinceNanos != null || captureNanos <= resumedAtNanos) return null
        val micros = (captureNanos - startNanos - pausedTotalNanos) / 1_000
        if (micros <= lastTimestampMicros) return null
        lastTimestampMicros = micros
        return micros
    }

    /** Recorded time up to [nowNanos], without the pauses. */
    @Synchronized
    fun recordedMillis(nowNanos: Long): Long {
        val pausedNow = pausedSinceNanos?.let { nowNanos - it } ?: 0L
        return (nowNanos - startNanos - pausedTotalNanos - pausedNow) / 1_000_000
    }
}
