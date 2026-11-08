@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset3840x2160
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeInvalid
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The Info.plist key an app needs before it may touch the microphone; TCC kills it otherwise. */
internal const val MICROPHONE_USAGE_KEY = "NSMicrophoneUsageDescription"

/** The session preset a recording of [this] quality runs at (w6 decision G2). */
internal val VideoQuality.sessionPreset: String
    get() = when (this) {
        VideoQuality.SD -> AVCaptureSessionPreset640x480!!
        VideoQuality.HD -> AVCaptureSessionPreset1280x720!!
        VideoQuality.FHD -> AVCaptureSessionPreset1920x1080!!
        VideoQuality.UHD -> AVCaptureSessionPreset3840x2160!!
    }

/**
 * The iOS engine's recordings, on AVCaptureMovieFileOutput (Decision 12, design §5). Every movie call
 * runs on the session queue, after the transaction that added the movie output (and the microphone,
 * for audio, and the quality's preset) has committed; AVFoundation's delegate callbacks hop there too,
 * so the recorder's state is the session queue's alone.
 *
 * - Every recording the engine started yields exactly one Finalized on [events], whether the app ended
 *   it or AVFoundation did, mapped by [movieEnd] under the partial-file rule: an unplayable file is
 *   deleted. A recording whose end does not arrive within [finishTimeoutMs] ends
 *   `Finalized(Failure(Timeout(RECORD_STOP)), ERROR)`. A start that returns a failure started nothing
 *   and owes no Finalized. A start its caller gave up on (cancelled) that a stop then ends yields
 *   one, carrying the stop's outcome, even when AVFoundation never reported it started.
 * - A finished recording's output, microphone and preset leave the session, unless prepared
 *   ([prepare]): a prepared output stays until the camera stops ([releasePreparedOnQueue]), and only
 *   the preset leaves it, so stills between its recordings run at the still preset (w6 decision G2).
 */
internal class AppleRecorder(
    private val custom: CustomCameraController,
    private val cameraConfig: CameraConfiguration,
    osVersion: Int,
    /** Whether the app's Info.plist has a key. */
    var hasUsageDescription: (String) -> Boolean,
    /** The qualities the bound camera can record. */
    private val supportedQualities: () -> Set<VideoQuality>,
    /** How a movie's video connection is turned when it starts. */
    private val videoRotation: () -> VideoRotation,
) {
    /** The movie-file-output calls a recording makes; tests record them. */
    var movieRecorder: MovieRecorder = AVMovieRecorder

    /** The microphone's session input for a recording with audio, or why there is none. */
    var microphoneInput: () -> Result<AVCaptureInput> = ::defaultMicrophoneInput

    /** How long startRecording waits for AVFoundation to report the recording started. */
    var startTimeoutMs: Long = 10_000

    /** How long a stop waits for AVFoundation to finish the file. */
    var finishTimeoutMs: Long = 10_000

    /** Session queue: a recording finalized and nothing records now, so the outputs may change again. */
    var onIdle: () -> Unit = {}

    /** The duration of the finished movie at a path, or null when it cannot be read; tests answer for the fake output. */
    var fileDuration: (path: String) -> Duration? = ::movieFileDuration

    /** Whether the movie output can pause: AVCaptureFileOutput's pause exists from iOS 18. */
    var pauseSupported: () -> Boolean = { osVersion >= PAUSE_RESUME_IOS }

    private val _events = MutableSharedFlow<EngineRecordingEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: Flow<EngineRecordingEvent> = _events.asSharedFlow()

    @Volatile
    private var recording = false

    /** Whether a recording is starting, running or finishing: the session's outputs are frozen. */
    val isActive: Boolean get() = recording

    // Session queue only.
    private var active: Movie? = null
    private var prepared: Prepared? = null

    private val audioRefusalLogged = atomic(false)

    suspend fun prepare(config: VideoConfiguration): CameraKResult<Unit> {
        refusal(config, CameraOperation.RECORD_PREPARE)?.let { return it }
        return onQueue("preparing a recording") {
            when {
                active != null -> failure(CameraKError.Busy(CameraOperation.RECORD_PREPARE))
                !custom.isConfigured -> failure(CameraKError.NotReady)
                prepared?.matches(config) == true -> CameraKResult.Success(Unit)
                else -> {
                    releasePreparedOnQueue()
                    configure(config).fold(
                        onSuccess = { setup ->
                            prepared = Prepared(config, setup)
                            CameraKResult.Success(Unit)
                        },
                        onFailure = { failure(it.toCameraKError()) },
                    )
                }
            }
        }
    }

    suspend fun start(config: VideoConfiguration): CameraKResult<String> {
        refusal(config, CameraOperation.RECORD_START)?.let { return it }
        val path = try {
            moviePath(config)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "creating the recording's directory failed", e)
            return failure(CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e))
        }
        val movie = when (val begun = onQueue("starting a recording") { begin(config, path) }) {
            is CameraKResult.Failure -> return begun
            is CameraKResult.Success -> begun.value
        }
        return withTimeoutOrNull(startTimeoutMs) { movie.started.await() } ?: run {
            CameraKLogger.e(TAG, "the recording did not start within $startTimeoutMs ms")
            val reportedStarted = onQueue("ending a recording that did not start in time") {
                CameraKResult.Success(endLateStart(movie))
            }
            // A recording AVFoundation reported started as the timeout fired was announced Started, so
            // it owes its one Finalized: the start returns once that has been emitted.
            if ((reportedStarted as? CameraKResult.Success)?.value == true) movie.finished.await()
            failure(CameraKError.Timeout(CameraOperation.RECORD_START))
        }
    }

    /** Ends the recording for [reason] and returns its result once AVFoundation has finished the file. */
    suspend fun stop(reason: RecordingEndReason): CameraKResult<RecordedVideo> {
        val movie = onQueue("stopping a recording") {
            val movie = active?.takeIf { !it.abandoned } ?: return@onQueue failure(stopInvalid)
            requestEnd(movie, reason)
            CameraKResult.Success(movie)
        }
        return when (movie) {
            is CameraKResult.Failure -> movie
            is CameraKResult.Success -> movie.value.finished.await()
        }
    }

    /**
     * Pauses the recording and returns once the movie output reports it paused. AVCaptureMovieFileOutput
     * applies a pause on its own queue, so isRecordingPaused still reads false straight after
     * pauseRecording; the session queue checks it again every [OUTPUT_STATE_POLL_MS] for up to
     * [OUTPUT_STATE_TIMEOUT_MS].
     */
    suspend fun pause(): CameraKResult<Unit> = onQueueAwaiting("pausing a recording") { done ->
        val movie = active?.takeIf { it.started.isCompleted && !it.abandoned }
            ?: return@onQueueAwaiting done(failure(CameraKError.InvalidState(CameraOperation.RECORD_PAUSE)))
        if (!pauseSupported()) {
            return@onQueueAwaiting done(
                failure(CameraKError.Unsupported(CameraFeature.PAUSE_RESUME)),
            )
        }
        movieRecorder.pause(movie.output)
        whenOutputReports({ movieRecorder.isPaused(movie.output) }, done) { paused ->
            when {
                movie.finalized -> failure(CameraKError.InvalidState(CameraOperation.RECORD_PAUSE))
                !paused -> failure(
                    CameraKError.Hardware(
                        null,
                        IllegalStateException("The movie output did not pause within $OUTPUT_STATE_TIMEOUT_MS ms"),
                    ),
                )
                else -> {
                    if (movie.pausedAt == null) movie.pausedAt = TimeSource.Monotonic.markNow()
                    _events.tryEmit(EngineRecordingEvent.Paused)
                    CameraKResult.Success(Unit)
                }
            }
        }
    }

    /** Resumes a paused recording and returns once the movie output reports it recording again ([pause]). */
    suspend fun resume(): CameraKResult<Unit> = onQueueAwaiting("resuming a recording") { done ->
        val movie = active?.takeIf { it.started.isCompleted && !it.abandoned }
            ?: return@onQueueAwaiting done(failure(CameraKError.InvalidState(CameraOperation.RECORD_RESUME)))
        if (!pauseSupported()) {
            return@onQueueAwaiting done(
                failure(CameraKError.Unsupported(CameraFeature.PAUSE_RESUME)),
            )
        }
        if (movie.pausedAt == null) {
            return@onQueueAwaiting done(failure(CameraKError.InvalidState(CameraOperation.RECORD_RESUME)))
        }
        movieRecorder.resume(movie.output)
        whenOutputReports({ !movieRecorder.isPaused(movie.output) }, done) { resumed ->
            when {
                movie.finalized -> failure(CameraKError.InvalidState(CameraOperation.RECORD_RESUME))
                !resumed -> failure(
                    CameraKError.Hardware(
                        null,
                        IllegalStateException("The movie output did not resume within $OUTPUT_STATE_TIMEOUT_MS ms"),
                    ),
                )
                else -> {
                    movie.pausedAt?.let { movie.paused += it.elapsedNow() }
                    movie.pausedAt = null
                    _events.tryEmit(EngineRecordingEvent.Resumed)
                    CameraKResult.Success(Unit)
                }
            }
        }
    }

    /**
     * Session queue: passes to [done] what [then] makes of whether [applied] held, checked now and then
     * every [OUTPUT_STATE_POLL_MS] until it holds or [OUTPUT_STATE_TIMEOUT_MS] have passed. The queue
     * is free between checks.
     */
    private fun whenOutputReports(
        applied: () -> Boolean,
        done: (CameraKResult<Unit>) -> Unit,
        waitedMs: Long = 0,
        then: (Boolean) -> CameraKResult<Unit>,
    ) {
        val holds = applied()
        if (holds || waitedMs >= OUTPUT_STATE_TIMEOUT_MS) return done(then(holds))
        custom.onSessionQueueAfter(OUTPUT_STATE_POLL_MS) {
            try {
                whenOutputReports(applied, done, waitedMs + OUTPUT_STATE_POLL_MS, then)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "reading the movie output's paused state failed", e)
                done(failure(CameraKError.Internal(e)))
            }
        }
    }

    /** The engine's stop: a running recording ends with SHUTDOWN before the camera stops (contract rule 7). */
    suspend fun finishForStop() {
        val result = stop(RecordingEndReason.SHUTDOWN)
        val error = (result as? CameraKResult.Failure)?.error
        if (error != null && error !is CameraKError.InvalidState) {
            CameraKLogger.w(TAG, "the recording ended by the camera's stop failed: ${error::class.simpleName}")
        }
    }

    /**
     * The engine's close: a running recording ends with SHUTDOWN, and [then] runs once it has finalized
     * (or its finish timed out), so the session is released after the file is complete. A start still
     * waiting for AVFoundation gets Closed and started nothing.
     */
    fun finishForClose(then: () -> Unit) {
        custom.afterQueuedSessionWork {
            val movie = active
            if (movie == null || movie.finalized) {
                releasePreparedOnQueue()
                then()
                return@afterQueuedSessionWork
            }
            if (!movie.started.isCompleted) {
                movie.abandoned = true
                movie.started.complete(failure(CameraKError.Closed))
            }
            movie.afterFinalized += {
                releasePreparedOnQueue()
                then()
            }
            requestEnd(movie, RecordingEndReason.SHUTDOWN)
        }
    }

    /** Session queue: removes a prepared movie output that no recording uses. */
    fun releasePreparedOnQueue() {
        val kept = prepared ?: return
        if (active?.setup === kept.setup) return
        prepared = null
        custom.releaseMovieOnQueue(kept.setup.output, kept.setup.microphone)
    }

    // ─── Session queue ───────────────────────────────────────────────────────────────────────────

    private fun begin(config: VideoConfiguration, path: String): CameraKResult<Movie> {
        if (active != null) return failure(CameraKError.Busy(CameraOperation.RECORD_START))
        if (!custom.isConfigured) return failure(CameraKError.NotReady)
        val reused = prepared?.takeIf { it.matches(config) }
        val setup = reused?.setup ?: run {
            releasePreparedOnQueue()
            configure(config).getOrElse { return failure(it.toCameraKError()) }
        }
        if (reused != null && !reused.runsItsPreset) {
            // An earlier recording on this prepared output returned the session to the still preset.
            custom.setSessionPresetOnQueue(config.quality.sessionPreset)
            reused.runsItsPreset = true
        }
        applyLimits(setup.output, config)
        val delegate = VideoRecordingDelegate()
        val movie = Movie(config, path, setup, delegate, prepared = reused != null)
        delegate.onPlatformStarted =
            { custom.afterQueuedSessionWork { guarded("a recording's start") { onStarted(movie) } } }
        delegate.onPlatformFinished = { error ->
            custom.afterQueuedSessionWork { guarded("a recording's end") { onFinished(movie, error) } }
        }
        active = movie
        recording = true
        removeFileIfPresent(path)
        movieRecorder.start(setup.output, NSURL.fileURLWithPath(path), videoRotation(), delegate)
        return CameraKResult.Success(movie)
    }

    /** The movie output, the microphone for audio and the quality's preset, in one committed transaction. */
    private fun configure(config: VideoConfiguration): Result<MovieSetup> {
        val output = AVCaptureMovieFileOutput()
        return custom.configureMovieOnQueue(
            output,
            microphone = microphoneInput.takeIf { config.enableAudio },
            preset = config.quality.sessionPreset,
        ).map { microphone -> MovieSetup(output, microphone) }
    }

    /**
     * Keeps [MIN_FREE_DISK_BYTES] free (AVFoundation ends the recording, still playable, before the disk
     * fills) and stops at the configured duration and size; AVFoundation's 0 and invalid time are "no limit".
     */
    private fun applyLimits(output: AVCaptureMovieFileOutput, config: VideoConfiguration) {
        output.minFreeDiskSpaceLimit = MIN_FREE_DISK_BYTES
        val maxDuration = config.maxDuration
        output.maxRecordedDuration = if (maxDuration != null && maxDuration.isPositive()) {
            CMTimeMakeWithSeconds(maxDuration.inWholeMilliseconds / 1000.0, 600)
        } else {
            kCMTimeInvalid.readValue()
        }
        output.maxRecordedFileSize = config.maxFileSizeBytes ?: 0L
    }

    private fun onStarted(movie: Movie) {
        if (movie.abandoned || movie.finalized) return
        movie.startedAt = TimeSource.Monotonic.markNow()
        movie.hasAudio = movie.setup.microphone != null && movieRecorder.hasActiveAudioConnection(movie.output)
        _events.tryEmit(EngineRecordingEvent.Started(movie.path, movie.hasAudio))
        movie.started.complete(CameraKResult.Success(movie.path))
    }

    private fun onFinished(movie: Movie, error: NSError?) {
        if (movie.finalized) {
            if (movie.abandoned && movie.startedAt == null && removeFileIfPresent(movie.path)) {
                CameraKLogger.w(TAG, "deleted the file AVFoundation finished for an abandoned recording start")
            } else {
                CameraKLogger.d(TAG, "AVFoundation finished a recording CameraK had already ended")
            }
            return
        }
        val end = movieEnd(error, movie.stopReason, movie.path)
        val playable = error == null || error.recordedPlayably
        if (!playable && !removeFileIfPresent(movie.path)) {
            CameraKLogger.d(TAG, "an unplayable recording left no file to delete")
        }
        if (movie.startedAt == null) {
            // It ended before it started: the start fails with why. Ended by itself, it started nothing
            // and a later stop finds nothing to stop. Ended by a stop (the start was given up on), that
            // stop's answer is not InvalidState, so it yields its one Finalized (CameraEngine.recordingEvents).
            if (movie.stopReason == null) movie.abandoned = true
            val why = end.error ?: CameraKError.Internal(IllegalStateException("The recording ended before it started"))
            finalize(movie, failure(why), end.reason)
            movie.started.complete(failure(why))
            return
        }
        val result = end.error?.let(::failure) ?: CameraKResult.Success(video(movie))
        finalize(movie, result, end.reason)
    }

    /** Asks AVFoundation to finish [movie] for [reason], and ends it as timed out if it does not in time. */
    private fun requestEnd(movie: Movie, reason: RecordingEndReason) {
        if (movie.stopReason != null) return
        movie.stopReason = reason
        movieRecorder.stop(movie.output)
        custom.onSessionQueueAfter(finishTimeoutMs) {
            if (movie.finalized) return@onSessionQueueAfter
            CameraKLogger.e(TAG, "the recording did not finish within $finishTimeoutMs ms")
            finalize(movie, failure(CameraKError.Timeout(CameraOperation.RECORD_STOP)), RecordingEndReason.ERROR)
        }
    }

    /**
     * The start of [movie] timed out. When AVFoundation reported it started meanwhile, it is stopped
     * like any recording and yields its Finalized (returns true); otherwise it is abandoned (false).
     */
    private fun endLateStart(movie: Movie): Boolean {
        if (movie.startedAt == null || movie.finalized) {
            abandon(movie)
            return false
        }
        requestEnd(movie, RecordingEndReason.ERROR)
        return true
    }

    /**
     * A start given up on: AVFoundation is told to stop, and no Finalized is owed for it. The file
     * AVFoundation finishes for it afterwards is deleted ([onFinished]).
     */
    private fun abandon(movie: Movie) {
        if (movie.finalized) return
        movie.abandoned = true
        if (movie.stopReason == null) {
            movie.stopReason = RecordingEndReason.ERROR
            movieRecorder.stop(movie.output)
        }
        finalize(movie, failure(CameraKError.Timeout(CameraOperation.RECORD_START)), RecordingEndReason.ERROR)
    }

    private fun finalize(movie: Movie, result: CameraKResult<RecordedVideo>, reason: RecordingEndReason) {
        movie.finalized = true
        if (movie.prepared) {
            stillPresetAfter(movie)
        } else {
            custom.releaseMovieOnQueue(movie.output, movie.setup.microphone)
        }
        if (active === movie) {
            active = null
            recording = false
            guarded("changing the outputs after a recording") { onIdle() }
        }
        if (!movie.abandoned) {
            if (!_events.tryEmit(EngineRecordingEvent.Finalized(result, reason))) {
                CameraKLogger.e(TAG, "a recording's Finalized event was dropped: the event buffer is full")
            }
        }
        movie.finished.complete(result)
        movie.afterFinalized.forEach { it() }
    }

    /** The session back at the still preset after a recording on the prepared output, which stays. */
    private fun stillPresetAfter(movie: Movie) {
        val kept = prepared?.takeIf { it.setup === movie.setup && it.runsItsPreset } ?: return
        if (movie.config.quality.sessionPreset == custom.stillPreset) return
        custom.setSessionPresetOnQueue(null)
        kept.runsItsPreset = false
    }

    /** How long [movie] has recorded: from its start, less its pauses. */
    private fun recorded(movie: Movie): Duration {
        val elapsed = movie.startedAt?.elapsedNow() ?: Duration.ZERO
        val paused = movie.paused + (movie.pausedAt?.elapsedNow() ?: Duration.ZERO)
        return (elapsed - paused).coerceAtLeast(Duration.ZERO)
    }

    /**
     * The finished file's duration, which is what was recorded; the wall clock less the pauses only
     * when the file's cannot be read.
     */
    private fun duration(movie: Movie): Duration {
        val fromFile = try {
            fileDuration(movie.path)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "reading the finished movie's duration failed", e)
            null
        }
        return fromFile ?: recorded(movie).also {
            CameraKLogger.w(TAG, "the finished movie's duration could not be read; reporting the time it recorded for")
        }
    }

    private fun video(movie: Movie): RecordedVideo = RecordedVideo(
        path = movie.path,
        duration = duration(movie),
        width = movie.config.quality.width,
        height = movie.config.quality.height,
        bitrateBps = null,
        hasAudio = movie.hasAudio,
    )

    // ─── Any thread ──────────────────────────────────────────────────────────────────────────────

    /** Why [config] cannot be recorded on this camera, before anything reaches the session. */
    private fun refusal(config: VideoConfiguration, operation: CameraOperation): CameraKResult.Failure? {
        if (config.enableAudio && !hasUsageDescription(MICROPHONE_USAGE_KEY)) {
            val cause = IllegalStateException(
                "Recording with audio needs $MICROPHONE_USAGE_KEY in the app's Info.plist; " +
                    "without it iOS ends the app at the first microphone call",
            )
            if (audioRefusalLogged.compareAndSet(expect = false, update = true)) {
                CameraKLogger.e(TAG, "$operation with audio refused", cause)
            }
            return failure(CameraKError.Unsupported(CameraFeature.AUDIO, cause))
        }
        if (config.quality !in supportedQualities()) {
            return failure(CameraKError.Unsupported(CameraFeature.VIDEO_QUALITY))
        }
        return null
    }

    /**
     * A new file in app-owned storage: the recording's output directory, the camera's, else
     * Documents/CameraK. Publishing to Photos is the media saver's job (Decision 10).
     */
    private fun moviePath(config: VideoConfiguration): String {
        val directory = appOwnedDirectory(config.outputDirectory ?: cameraConfig.outputDirectory)
        val timestamp = NSDate().timeIntervalSince1970.toLong()
        return "$directory/${config.filePrefix}_${timestamp}_${NSUUID().UUIDString.take(8)}.mp4"
    }

    /** Runs [block] on the session queue; its result is what it, or work it queues later, passes to done. */
    private suspend fun <T> onQueueAwaiting(
        what: String,
        block: (done: (CameraKResult<T>) -> Unit) -> Unit,
    ): CameraKResult<T> = suspendCancellableCoroutine { continuation ->
        custom.afterQueuedSessionWork {
            try {
                block { result -> continuation.resume(result) }
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "$what failed", e)
                continuation.resume(failure(CameraKError.Internal(e)))
            }
        }
    }

    private suspend fun <T> onQueue(what: String, block: () -> CameraKResult<T>): CameraKResult<T> =
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                val result = try {
                    block()
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "$what failed", e)
                    failure(CameraKError.Internal(e))
                }
                continuation.resume(result)
            }
        }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "handling $what failed", e)
        }
    }

    private fun Throwable.toCameraKError(): CameraKError {
        val platform = (cause as? NSErrorException)?.error ?: (this as? NSErrorException)?.error
        return when {
            platform != null -> cameraKErrorFor(platform)
            this is CustomCameraController.CameraException.DeviceNotAvailable -> CameraKError.NotReady
            else -> CameraKError.Hardware(null, this)
        }
    }

    /** A movie output and the microphone input added with it, if any. */
    private class MovieSetup(val output: AVCaptureMovieFileOutput, val microphone: AVCaptureInput?)

    private class Prepared(val config: VideoConfiguration, val setup: MovieSetup) {
        /** Whether the session runs the quality's preset, as prepare left it; false after a recording. */
        var runsItsPreset = true

        fun matches(other: VideoConfiguration) =
            other.enableAudio == config.enableAudio && other.quality == config.quality
    }

    private class Movie(
        val config: VideoConfiguration,
        val path: String,
        val setup: MovieSetup,
        val delegate: VideoRecordingDelegate,
        val prepared: Boolean,
    ) {
        val output: AVCaptureMovieFileOutput get() = setup.output
        val started = CompletableDeferred<CameraKResult<String>>()
        val finished = CompletableDeferred<CameraKResult<RecordedVideo>>()
        val afterFinalized = mutableListOf<() -> Unit>()
        var startedAt: TimeMark? = null
        var pausedAt: TimeMark? = null
        var paused = Duration.ZERO
        var hasAudio = false
        var stopReason: RecordingEndReason? = null
        var abandoned = false
        var finalized = false
    }

    private companion object {
        const val TAG = "CameraK"
        const val PAUSE_RESUME_IOS = 18

        /** How often, and for how long, pause and resume check that the movie output applied them. */
        const val OUTPUT_STATE_POLL_MS = 10L
        const val OUTPUT_STATE_TIMEOUT_MS = 1_000L
        const val EVENT_BUFFER = 64

        /** Free space a recording leaves on disk: room for the system and the file's finalisation. */
        const val MIN_FREE_DISK_BYTES = 200L * 1024 * 1024

        val stopInvalid = CameraKError.InvalidState(CameraOperation.RECORD_STOP)

        fun failure(error: CameraKError) = CameraKResult.Failure(error)
    }
}

/** The default microphone's input, or why there is none: AVFoundation's NSError for one it refuses. */
internal fun defaultMicrophoneInput(): Result<AVCaptureInput> = memScoped {
    val microphone = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio)
        ?: return@memScoped Result.failure(IllegalStateException("No microphone is available"))
    val error = alloc<ObjCObjectVar<NSError?>>()
    AVCaptureDeviceInput.deviceInputWithDevice(microphone, error.ptr)?.let { Result.success(it) }
        ?: Result.failure(
            error.value?.let(::NSErrorException)
                ?: IllegalStateException("AVFoundation made no input for the microphone and gave no error"),
        )
}

/** The duration of the movie at [path] as its file states it; null when the file has none to read. */
internal fun movieFileDuration(path: String): Duration? {
    val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), options = null)
    val seconds = CMTimeGetSeconds(asset.duration)
    return if (seconds.isFinite() && seconds > 0) seconds.seconds else null
}
