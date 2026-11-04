package com.kashif.cameraK.recording

/**
 * Presentation time for a recording: the capture time of each frame measured from the start of the
 * recording, with paused time taken out, so the video plays in real time and a pause leaves no gap.
 * Thread-safe: frames are stamped on the recording coroutine while pause and resume are called from
 * the app.
 */
internal class RecordingClock(private val startNanos: Long) {
    /** Starts a pause at [nowNanos]; does nothing while already paused. */
    fun pause(nowNanos: Long): Unit = TODO("H3' fix")

    /** Ends the pause at [nowNanos]; does nothing unless paused. */
    fun resume(nowNanos: Long): Unit = TODO("H3' fix")

    /**
     * The presentation timestamp in microseconds for a frame captured at [captureNanos], or null if
     * the frame belongs to no recorded stretch: captured before the start, while paused or before the
     * last resume, or not after the previous frame (an encoder needs strictly increasing times).
     */
    fun timestampMicros(captureNanos: Long): Long? = TODO("H3' fix")

    /** Recorded time up to [nowNanos], without the pauses. */
    fun recordedMillis(nowNanos: Long): Long = TODO("H3' fix")
}
