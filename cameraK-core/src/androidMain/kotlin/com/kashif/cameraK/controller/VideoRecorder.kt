@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.nanoseconds

private const val TAG = "CameraK"

/** How long a stop waits for CameraX's Finalize, and a start, pause or resume for CameraX's report. */
internal const val STOP_FINALIZE_TIMEOUT_MS = 5000L

private const val EVENT_BUFFER = 64

/** What [VideoRecorder] needs from the session that binds the camera. Main thread. */
internal interface VideoSessionHost {
    val isReleased: Boolean

    /** Whether a bind has given the session its camera. */
    val isBound: Boolean

    /**
     * Rebinds the bound camera with [VideoRecorder.useCaseForBind] in the group; null when bound.
     * When CameraX refuses the group, the host binds it again without VideoCapture and returns
     * `Unsupported(VIDEO_QUALITY)`.
     */
    fun bindWithVideo(): CameraKError?

    /** Rebinds the bound camera with the current use cases and waits for the bind. */
    suspend fun rebindAndAwait()

    /** CameraX ended the active recording by itself and VideoCapture was dropped: rebind for full-FOV stills. */
    fun rebindAfterRecordingEndedByCameraX()

    /** No recording is active any more. */
    fun onNoActiveRecording()

    /** The qualities the bound camera records, once the last bind's query has returned. */
    suspend fun videoQualities(): Set<VideoQuality>
}

/** How one recording ended, with what the 1.x facade also reports. */
internal class RecordingOutcome(
    val result: CameraKResult<RecordedVideo>,
    val reason: RecordingEndReason,
    val durationMs: Long,
)

/**
 * Video recording (design §5, §2.4): one CameraX recording per start, observed from its Start to its
 * Finalize, and every milestone on [events]. VideoCapture joins the bind only when a recording needs
 * it, or from [prepare] until the engine stops (#136: while bound, stills crop to its field of view).
 * The Recorder and its VideoCapture are built on [background]; only the bind runs on Main. Each
 * Finalize is mapped by [FinalizeMapper] over the playable probe, and a recording whose file is not
 * kept leaves no file.
 *
 * Every recording the engine started yields exactly one `Finalized`, in start order, whoever ended
 * it. A start that returns Failure started none. A start whose caller gave up after it reached
 * CameraX stays the active recording, so the next stop ends it and it still yields its `Finalized`.
 *
 * The 1.x facade records here too ([start] with `legacy`): a recording it started whose outcome no
 * facade stop claimed is kept for the next facade stop, as 1.x reported it.
 */
internal class VideoRecorder(
    private val context: Context,
    private val backend: CameraXBackend,
    private val files: OutputFileFactory,
    private val host: VideoSessionHost,
    private val background: CoroutineDispatcher,
    private val callbackExecutor: Executor,
) {
    /** The VideoCapture of the current bind, or null when it has none. */
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    // The quality videoCapture records in, and whether it stays bound after its recording (prepare).
    private var videoQuality: VideoQuality? = null
    private var prepared = false

    // Set when the engine stopped with VideoCapture bound: the next start binds the photo session again.
    var videoBindStale = false
        private set

    // The recording CameraX is running, from its start until a stop or its Finalize.
    private var active: Run? = null

    // A recording the 1.x facade started, ended, and whose outcome no facade stop has claimed yet.
    private var unclaimed: Run? = null

    private val _events = MutableSharedFlow<EngineRecordingEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<EngineRecordingEvent> = _events.asSharedFlow()

    val isRecording: Boolean get() = active != null

    /** The VideoCapture the next bind's use-case group includes, or null. */
    fun useCaseForBind(): VideoCapture<Recorder>? = videoCapture

    /** Takes VideoCapture out of the next bind: CameraX refused the group that had it. */
    fun dropVideoCapture() {
        videoCapture = null
        videoQuality = null
    }

    /** The engine bound the photo session again without VideoCapture. */
    fun onBoundWithoutVideo() {
        videoBindStale = false
    }

    /** Binds VideoCapture for [config] ahead of a start; it stays until the engine stops. Main thread. */
    suspend fun prepare(config: VideoConfiguration): CameraKResult<Unit> {
        if (active != null) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_PREPARE))
        unsupportedQuality(config)?.let { return it }
        bindVideoCapture(config.quality)?.let { return CameraKResult.Failure(it) }
        prepared = true
        return CameraKResult.Success(Unit)
    }

    /**
     * Starts a recording and returns its file's path once CameraX reports the start. Main thread.
     * [legacy] marks a 1.x facade recording, whose unclaimed outcome is kept for the next facade stop.
     */
    suspend fun start(config: VideoConfiguration, legacy: Boolean): CameraKResult<String> {
        if (active != null) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_START))
        unsupportedQuality(config)?.let { return it }
        if (legacy && unclaimed != null) {
            CameraKLogger.w(TAG, "The last recording's outcome was never claimed by stopRecording(); discarding it")
            unclaimed = null
        }
        val lazyBind = videoCapture == null || videoQuality != config.quality
        var started = false
        try {
            if (lazyBind) bindVideoCapture(config.quality)?.let { return CameraKResult.Failure(it) }
            val file = when (val claimed = files.videoFile(config)) {
                is CameraKResult.Failure -> return claimed
                is CameraKResult.Success -> claimed.value
            }
            val run = Run(file, config, withAudio = config.enableAudio && hasAudioPermission(), legacy)
            active = run
            try {
                run.session = backend.startRecording(
                    videoCapture = checkNotNull(videoCapture) { "a recording starts on a bound VideoCapture" },
                    context = context,
                    output = outputOptions(file, config),
                    withAudio = run.withAudio,
                    executor = callbackExecutor,
                ) { event -> onEvent(run, event) }
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "CameraX refused to start the recording", e)
                if (active === run) active = null
                file.delete()
                return CameraKResult.Failure(CameraKError.Internal(e))
            }
            return awaitStart(run).also { started = it is CameraKResult.Success }
        } catch (e: CancellationException) {
            started = active != null
            throw e
        } finally {
            // A start that began nothing drops the VideoCapture it bound, so stills are full-FOV (#136).
            if (!started && !prepared && active == null && videoCapture != null) {
                withContext(NonCancellable) { dropVideoCaptureAndRebind() }
            }
        }
    }

    private suspend fun awaitStart(run: Run): CameraKResult<String> {
        run.awaitingStart = true
        val ended = try {
            withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) {
                select<RecordingOutcome?> {
                    run.started.onAwait { null }
                    run.finalized.onAwait { it }
                }
            }
        } catch (e: CancellationException) {
            // The caller gave up after CameraX was asked: the next stop ends this recording, which then
            // yields its Finalized. A 1.x caller never stops what it gave up on, so it is stopped now.
            run.awaitingStart = false
            if (run.legacy) {
                if (active === run) active = null
                stopSession(run, RecordingEndReason.USER)
            }
            throw e
        }
        run.awaitingStart = false
        return when {
            run.startConfirmed -> CameraKResult.Success(run.file.absolutePath)
            ended != null -> CameraKResult.Failure(
                (ended.result as? CameraKResult.Failure)?.error
                    ?: CameraKError.Internal(
                        IllegalStateException("CameraX finalized the recording before it started"),
                    ),
            )
            else -> {
                CameraKLogger.w(TAG, "CameraX did not report the recording's start in time; stopping it")
                run.startFailed = true
                if (active === run) active = null
                stopSession(run, RecordingEndReason.ERROR)
                CameraKResult.Failure(CameraKError.Timeout(CameraOperation.RECORD_START))
            }
        }
    }

    /** Stops the active recording and returns how it ended; `InvalidState` when nothing records. Main thread. */
    suspend fun stop(): CameraKResult<RecordedVideo> {
        val run = active ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_STOP))
        return stopRun(run, RecordingEndReason.USER, rebind = true).result
    }

    /**
     * The 1.x facade's stop: the active recording's outcome, or the last one it started that ended
     * unclaimed, or null when there is neither. A cancelled caller leaves the outcome for the next stop.
     */
    suspend fun stopForFacade(): RecordingOutcome? {
        unclaimed?.let { run ->
            if (active == null) {
                unclaimed = null
                return run.finalized.await()
            }
        }
        val run = active ?: return null
        val outcome = stopRun(run, RecordingEndReason.USER, rebind = true)
        run.claimed = true
        if (unclaimed === run) unclaimed = null
        return outcome
    }

    /**
     * Finalizes the active recording before the camera stops or closes (§2.3 rule 7): it ends with
     * SHUTDOWN, and the VideoCapture it used goes with the session. Main thread.
     */
    suspend fun stopForShutdown() {
        active?.let { stopRun(it, RecordingEndReason.SHUTDOWN, rebind = false) }
        if (videoCapture != null) {
            videoCapture = null
            videoQuality = null
            prepared = false
            videoBindStale = true
        }
    }

    private suspend fun stopRun(run: Run, reason: RecordingEndReason, rebind: Boolean): RecordingOutcome {
        if (active === run) active = null
        try {
            stopSession(run, reason)
            val outcome = withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) { run.finalized.await() }
            if (outcome == null) CameraKLogger.w(TAG, "CameraX did not finalize the recording in time")
            return outcome ?: RecordingOutcome(
                CameraKResult.Failure(CameraKError.Timeout(CameraOperation.RECORD_STOP)),
                RecordingEndReason.ERROR,
                durationMs = 0,
            )
        } finally {
            // Back to the full-FOV photo session before returning, also for a cancelled caller, so an
            // immediate still or start never races a pending bind (#136).
            if (rebind && !prepared && active == null && videoCapture != null) {
                withContext(NonCancellable) { dropVideoCaptureAndRebind() }
            }
            if (active == null) host.onNoActiveRecording()
        }
    }

    // Asks CameraX to stop [run] once. A stop CameraX throws from (its recorder already torn down)
    // will never finalize, so the recording ends here.
    private fun stopSession(run: Run, reason: RecordingEndReason) {
        if (run.stopReason == null) run.stopReason = reason
        val session = run.session ?: return
        try {
            session.stop()
        } catch (e: Exception) {
            val operation = if (reason == RecordingEndReason.SHUTDOWN) "on release" else "on request"
            CameraKLogger.e(TAG, "Stopping the recording $operation failed", e)
            finish(run, RecordingOutcome(CameraKResult.Failure(CameraKError.Internal(e)), reason, durationMs = 0))
        }
    }

    /** Pauses the active recording and returns once CameraX reports the pause. Main thread. */
    suspend fun pause(): CameraKResult<Unit> = pauseOrResume(CameraOperation.RECORD_PAUSE, pausing = true)

    /** Resumes the paused recording and returns once CameraX reports the resume. Main thread. */
    suspend fun resume(): CameraKResult<Unit> = pauseOrResume(CameraOperation.RECORD_RESUME, pausing = false)

    private suspend fun pauseOrResume(operation: CameraOperation, pausing: Boolean): CameraKResult<Unit> {
        val run = active?.takeIf { it.startConfirmed }
            ?: return CameraKResult.Failure(CameraKError.InvalidState(operation))
        if (run.paused == pausing) return CameraKResult.Success(Unit)
        val confirmed = CompletableDeferred<Unit>()
        run.pendingToggle = confirmed
        try {
            if (pausing) run.session?.pause() else run.session?.resume()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Asking CameraX to ${if (pausing) "pause" else "resume"} the recording failed", e)
            run.pendingToggle = null
            return CameraKResult.Failure(CameraKError.Internal(e))
        }
        val reported = withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) { confirmed.await() }
        if (run.pendingToggle === confirmed) run.pendingToggle = null
        return when {
            reported == null -> CameraKResult.Failure(CameraKError.Timeout(operation))
            run.done -> CameraKResult.Failure(CameraKError.InvalidState(operation))
            else -> CameraKResult.Success(Unit)
        }
    }

    // On the callback executor, the engine's main dispatcher: every milestone of [run], in order.
    private fun onEvent(run: Run, event: RecordingEvent) {
        try {
            when (event) {
                RecordingEvent.Started -> {
                    if (run.startFailed || run.done) return
                    run.startConfirmed = true
                    emit(EngineRecordingEvent.Started(run.file.absolutePath, run.withAudio))
                    run.started.complete(Unit)
                }
                RecordingEvent.Paused, RecordingEvent.Resumed -> {
                    if (!run.startConfirmed || run.done) return
                    run.paused = event == RecordingEvent.Paused
                    emit(if (run.paused) EngineRecordingEvent.Paused else EngineRecordingEvent.Resumed)
                    run.pendingToggle?.complete(Unit)
                }
                is RecordingFinalized -> onFinalized(run, event)
            }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Handling a recording report failed", e)
        }
    }

    private fun onFinalized(run: Run, event: RecordingFinalized) {
        if (run.done) return
        // Finalized before CameraX reported the start, while the start still waits: it started nothing.
        if (!run.startConfirmed && run.awaitingStart) run.startFailed = true
        val probe = probe(run.file)
        val video = RecordedVideo(
            path = run.file.absolutePath,
            duration = event.recordedDurationNanos.nanoseconds,
            width = probe.width ?: run.config.quality.width,
            height = probe.height ?: run.config.quality.height,
            bitrateBps = probe.bitrateBps,
            hasAudio = run.withAudio,
        )
        val mapped = FinalizeMapper.mapRecording(
            event,
            probe.playable,
            video,
            run.stopReason ?: RecordingEndReason.USER,
        )
        logFinalize(event, mapped)
        val keptFile = mapped.result is CameraKResult.Success ||
            ((mapped.result as CameraKResult.Failure).error as? CameraKError.Storage)?.keptFilePath != null
        if (!keptFile || run.startFailed) run.file.delete()
        val endedByCameraX = run.stopReason == null
        finish(run, RecordingOutcome(mapped.result, mapped.reason, event.recordedDurationNanos / 1_000_000))
        if (endedByCameraX && active == null && !prepared && videoCapture != null && !host.isReleased) {
            // Drop VideoCapture so stills are full-FOV again (#136).
            videoCapture = null
            videoQuality = null
            host.rebindAfterRecordingEndedByCameraX()
        }
        if (active == null) host.onNoActiveRecording()
    }

    // Ends [run] once: its outcome, and its one Finalized unless its start returned Failure.
    private fun finish(run: Run, outcome: RecordingOutcome) {
        if (run.done) return
        run.done = true
        if (active === run) active = null
        if (run.legacy && !run.claimed && !run.startFailed) unclaimed = run
        if (!run.startFailed) emit(EngineRecordingEvent.Finalized(outcome.result, outcome.reason))
        run.pendingToggle?.complete(Unit)
        run.finalized.complete(outcome)
    }

    private fun emit(event: EngineRecordingEvent) {
        if (!_events.tryEmit(event)) CameraKLogger.e(TAG, "A recording event was dropped: no room in the event buffer")
    }

    private fun logFinalize(event: RecordingFinalized, mapped: MappedFinalize) {
        if (!event.hasError) return
        when {
            event.error in FinalizeMapper.bugCodes ->
                CameraKLogger.e(
                    TAG,
                    "CameraX ended the recording with error ${event.error}, a CameraK bug",
                    event.cause,
                )
            mapped.result is CameraKResult.Success ->
                CameraKLogger.w(
                    TAG,
                    "Recording ended by CameraX error ${event.error}; its file is playable",
                    event.cause,
                )
            else -> CameraKLogger.e(TAG, "Recording failed with CameraX error ${event.error}", event.cause)
        }
    }

    // CameraX's quality, from the bound camera's own set (§6.3); the bitrate is the quality's.
    private suspend fun unsupportedQuality(config: VideoConfiguration): CameraKResult.Failure? {
        if (config.quality in host.videoQualities()) return null
        return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.VIDEO_QUALITY))
    }

    // Builds the Recorder and VideoCapture off Main, then binds them on Main; null when bound.
    private suspend fun bindVideoCapture(quality: VideoQuality): CameraKError? {
        if (!host.isBound) return CameraKError.NotReady
        val built = try {
            withContext(background) { backend.buildVideoCapture(quality, quality.bitrateBps) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Building the VideoCapture use case failed", e)
            return CameraKError.Internal(e)
        }
        videoCapture = built
        videoQuality = quality
        val error = host.bindWithVideo()
        if (error != null) {
            videoCapture = null
            videoQuality = null
        }
        return error
    }

    private suspend fun dropVideoCaptureAndRebind() {
        videoCapture = null
        videoQuality = null
        if (host.isBound && !host.isReleased) host.rebindAndAwait()
    }

    private fun outputOptions(file: File, config: VideoConfiguration): FileOutputOptions {
        // CameraX stops at either limit and finalizes a playable file (see FinalizeMapper). Its 0
        // means no limit, so a positive duration under a millisecond is kept as 1 ms.
        val durationLimitMillis = config.maxDuration?.takeIf { it.isPositive() }
            ?.inWholeMilliseconds?.coerceAtLeast(1L) ?: 0L
        return FileOutputOptions.Builder(file)
            .setDurationLimitMillis(durationLimitMillis)
            .setFileSizeLimit(config.maxFileSizeBytes ?: 0L)
            .build()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private class Probe(val playable: Boolean, val width: Int?, val height: Int?, val bitrateBps: Int?)

    // §4.1's playable probe: the file exists and MediaMetadataRetriever reads a duration above zero.
    private fun probe(file: File): Probe {
        if (!file.isFile || file.length() == 0L) return Probe(false, null, null, null)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun read(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
            Probe(
                playable = (read(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L) > 0L,
                width = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt(),
                height = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt(),
                bitrateBps = read(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt(),
            )
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "Reading the recorded file's metadata failed; treating it as not playable", e)
            Probe(false, null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CameraKLogger.w(TAG, "Releasing the metadata reader failed", e)
            }
        }
    }

    /** One recording CameraX is running or ran. Main thread. */
    private class Run(val file: File, val config: VideoConfiguration, val withAudio: Boolean, val legacy: Boolean) {
        var session: RecordingSession? = null
        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<RecordingOutcome>()
        var startConfirmed = false
        var awaitingStart = false
        var startFailed = false
        var stopReason: RecordingEndReason? = null
        var paused = false
        var pendingToggle: CompletableDeferred<Unit>? = null
        var claimed = false
        var done = false
    }
}
