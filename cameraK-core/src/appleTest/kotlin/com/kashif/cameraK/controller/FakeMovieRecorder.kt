package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

/**
 * A [MovieRecorder] that records each call instead of recording. A started recording stays "in
 * progress" until the test reports it finished through the delegate it was given, as AVFoundation
 * would, so tests decide when (and whether) a recording starts and ends.
 */
internal class FakeMovieRecorder : MovieRecorder {
    class Start(
        val output: AVCaptureMovieFileOutput,
        val url: NSURL,
        val rotation: VideoRotation,
        val delegate: VideoRecordingDelegate,
        val queue: String,
    ) {
        /** The orientation the video connection was set to; null when it was turned by an angle. */
        val orientation: AVCaptureVideoOrientation? get() = (rotation as? VideoRotation.Orientation)?.orientation
    }

    /** Whether pause takes effect; a pause the output does not apply leaves isPaused false. */
    var pauseTakesEffect = true

    /**
     * How long after pause or resume the output reports the change, as AVCaptureMovieFileOutput applies
     * it on its own queue: isPaused keeps its old value until this many milliseconds have passed.
     */
    var pauseLatencyMs: Long = 0

    /** Whether the output's audio connection is active, for a recording whose microphone was added. */
    var audioConnectionActive = true

    /** Runs inside each start, before it is recorded: a test reads the session as AVFoundation finds it. */
    var onStart: (() -> Unit)? = null

    private val lock = NSLock()
    private val startList = mutableListOf<Start>()
    private var stopCount = 0
    private var pauseCount = 0
    private var recording = false
    private var paused = false
    private var pauseChangedAt = 0.0

    val starts: List<Start> get() = locked { startList.toList() }
    val stops: Int get() = locked { stopCount }
    val pauses: Int get() = locked { pauseCount }

    override fun isRecording(output: AVCaptureMovieFileOutput): Boolean = locked { recording }

    override fun start(
        output: AVCaptureMovieFileOutput,
        url: NSURL,
        rotation: VideoRotation,
        delegate: AVCaptureFileOutputRecordingDelegateProtocol,
    ) {
        onStart?.invoke()
        val queue = currentQueueLabel()
        locked {
            startList += Start(output, url, rotation, delegate as VideoRecordingDelegate, queue)
            recording = true
            paused = false
            pauseChangedAt = 0.0
        }
    }

    override fun stop(output: AVCaptureMovieFileOutput) {
        locked { stopCount++ }
    }

    override fun pause(output: AVCaptureMovieFileOutput) = locked {
        pauseCount++
        if (pauseTakesEffect) changePaused(true)
    }

    override fun resume(output: AVCaptureMovieFileOutput) = locked { changePaused(false) }

    override fun isPaused(output: AVCaptureMovieFileOutput): Boolean = locked {
        if (now() - pauseChangedAt >= pauseLatencyMs / 1000.0) paused else !paused
    }

    // Under the lock.
    private fun changePaused(to: Boolean) {
        if (paused == to) return
        paused = to
        pauseChangedAt = now()
    }

    private fun now(): Double = NSDate().timeIntervalSince1970

    override fun hasActiveAudioConnection(output: AVCaptureMovieFileOutput): Boolean = audioConnectionActive

    /** AVFoundation's didStartRecording callback for [start]. */
    fun reportStarted(start: Start) {
        start.delegate.captureOutput(
            start.output,
            didStartRecordingToOutputFileAtURL = start.url,
            fromConnections = emptyList<Any>(),
        )
    }

    /** AVFoundation's didFinishRecording callback for [start]; the output stops recording first. */
    fun reportFinished(start: Start, error: NSError?) {
        locked { recording = false }
        start.delegate.captureOutput(
            start.output,
            didFinishRecordingToOutputFileAtURL = start.url,
            fromConnections = emptyList<Any>(),
            error = error,
        )
    }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
