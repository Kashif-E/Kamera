package com.kashif.cameraK.recording

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Presentation time for a recording: the capture time of each frame measured from the start of the
 * recording, with paused time taken out, so the video plays in real time and a pause leaves no gap.
 * Thread-safe: frames are stamped on the recording coroutine while pause and resume are called from
 * the app.
 */
internal class RecordingClock(private val startNanos: Long) {
    private val lock = SynchronizedObject()
    private var pausedSinceNanos: Long? = null
    private var pausedTotalNanos = 0L
    private var resumedAtNanos = startNanos
    private var lastTimestampMicros = -1L

    /** Starts a pause at [nowNanos]; does nothing while already paused. */
    fun pause(nowNanos: Long): Unit = synchronized(lock) {
        if (pausedSinceNanos == null) pausedSinceNanos = nowNanos
    }

    /** Ends the pause at [nowNanos]; does nothing unless paused. */
    fun resume(nowNanos: Long): Unit = synchronized(lock) {
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
    fun timestampMicros(captureNanos: Long): Long? = synchronized(lock) {
        if (pausedSinceNanos != null || captureNanos <= resumedAtNanos) return null
        val micros = (captureNanos - startNanos - pausedTotalNanos) / 1_000
        if (micros <= lastTimestampMicros) return null
        lastTimestampMicros = micros
        micros
    }

    /** Recorded time up to [nowNanos], without the pauses. */
    fun recordedMillis(nowNanos: Long): Long = synchronized(lock) {
        val pausedNow = pausedSinceNanos?.let { nowNanos - it } ?: 0L
        (nowNanos - startNanos - pausedTotalNanos - pausedNow) / 1_000_000
    }
}
