package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One platform camera behind the holder. The holder is its only caller; apps and plugins never
 * call it. Every engine follows the contract below, which the shared contract kit checks on every
 * engine:
 *
 * 1. [start] returns Success only after the platform has accepted the start and the session is
 *    running or held by the platform, and [state] is then already [EngineState.Open] or
 *    [EngineState.Interrupted]; it reaches Open no earlier than the platform's confirmation. A
 *    start that fails, including `Unsupported(feature)` for a configuration the platform cannot
 *    honour, publishes `Failed(e)` before it returns `Failure(e)`; only `Failure(Closed)` after
 *    [close], or after a [stop] that cut the start short, leaves [state] at Closed. The engine
 *    reports Opening only inside [start], and Closed only after [stop], [close] or a cancelled
 *    start; a session that dies on its own is Failed, never Closed. **Failed ends its
 *    generation:** no later callback of that generation changes [state], even if the platform
 *    reopens on its own. From Failed, [stop] releases what is left and publishes Closed, and
 *    [start] begins a new generation.
 * 2. A cancelled [start], or a [stop] during [start], leaves the camera closed, including when the
 *    platform open arrives late. Every platform callback carries the start generation and is
 *    dropped when it belongs to an older generation or arrives after [stop] or [close]. An
 *    interruption that ends after [stop] leaves the engine Closed.
 * 3. **[close] is terminal.** Late platform callbacks do nothing. Calls made after close return
 *    `Failure(Closed)`, and a call still pending when close runs (a capture waiting on a callback)
 *    completes with `Failure(Closed)` at once. Native resources are released once, by their owner.
 * 4. Every platform callback that could throw is wrapped: no exception crosses a GCD block, an
 *    executor or a JNI callback.
 * 5. [hardware] and [zoom] change only from what the platform reports, never from the requested
 *    value. Every value comes from platform observers, except flash on Android and iOS: it has no
 *    observer there, because it is a capture parameter only the engine writes. Its report is a
 *    read-back of the platform object right after the platform call returns (Android's
 *    `ImageCapture.flashMode`; on iOS the mode the session-queue block stored after validating it
 *    against `photoOutput.supportedFlashModes`), published before [setFlashMode] returns, and
 *    [setFlashMode] returns that read-back. What is published is the platform's value, never the
 *    request.
 * 6. Desired hardware is re-applied after every bind, camera switch and restart.
 * 7. **[stop] or [close] during a recording finalizes the recording before the camera closes**,
 *    and the recording ends `Finalized(Success, SHUTDOWN)` with a complete file.
 * 8. Every call is cancellable at every point, including inside a blocking platform call.
 * 9. Every call runs on the engine's declared thread.
 * 10. **A platform's own cancellation of a superseded request** (CameraX's
 *    `OperationCanceledException` from a newer `setZoomRatio`) is not an error: it is logged at
 *    debug and never mapped to `Internal`. The holder never has more than one gesture-rate request
 *    in flight per engine.
 */
@CameraKEngineApi
public interface CameraEngine {
    public val state: StateFlow<EngineState>

    /**
     * Low-rate hardware values as the platform reports them: from platform observers, and flash on
     * Android and iOS from a read-back of the platform object published before [setFlashMode]
     * returns (rule 5).
     */
    public val hardware: StateFlow<HardwareState>

    /** Gesture-rate zoom, on its own stream. */
    public val zoom: StateFlow<ZoomState>

    public val deviceOrientation: StateFlow<DeviceOrientation>

    /** [CameraCapabilities.EMPTY] until the engine has enumerated its cameras; start and selectCamera wait for it. */
    public val capabilities: StateFlow<CameraCapabilities>

    /**
     * Platform-confirmed recording milestones, observed for the whole recording.
     *
     * Every recording the engine started yields exactly one [EngineRecordingEvent.Finalized],
     * whether the app ended it ([stopRecording], [stop], [close]) or the platform did. These arrive in
     * start order and may come after [stopRecording] returns. A recording is started once
     * [startRecording] returns Success or [EngineRecordingEvent.Started] is emitted; a
     * [startRecording] that returns Failure started none, and any Finalized it emitted came before it
     * returned. After a [startRecording] the holder gave up on (it timed out or was cancelled), the
     * holder calls [stopRecording]: `Failure(InvalidState(RECORD_STOP))` from it means nothing
     * reached the platform and no Finalized follows; any other outcome means that recording yields
     * its one Finalized like any other.
     */
    public val recordingEvents: Flow<EngineRecordingEvent>

    public val frames: FrameSource
    public val preview: PlatformPreviewSource

    /**
     * Applies the whole configuration plus [desired] and opens the camera. Returns Success once the
     * platform has accepted the start and [state] is already Open or Interrupted. Returns
     * `Failure(e)` with [state] already `Failed(e)`, except `Failure(Closed)` after close or a stop
     * during the start. Idempotent while accepted. From Closed or Failed it begins a new
     * generation. Cancellable at every point: a cancelled start leaves the camera closed.
     */
    public suspend fun start(desired: DesiredHardware): CameraKResult<Unit>

    /**
     * Finalizes an active recording (rule 7), then stops streaming and publishes Closed. From
     * Failed it releases what the failed session left and publishes Closed. Keeps the
     * configuration and the desired hardware. Idempotent.
     */
    public suspend fun stop()

    public suspend fun capture(): CameraKResult<CapturedImage>
    public suspend fun setZoom(ratio: Float): CameraKResult<ZoomState>
    public suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode>
    public suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode>

    /** Returns the camera that is live afterwards. */
    public suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo>
    public suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit>

    /** Adds the recording outputs ahead of time, so startRecording commits no configuration change. */
    public suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit>

    /** Returns once the platform reports recording started; the value is the output path. */
    public suspend fun startRecording(config: VideoConfiguration): CameraKResult<String>

    /** Returns once the file is finalized. */
    public suspend fun stopRecording(): CameraKResult<RecordedVideo>
    public suspend fun pauseRecording(): CameraKResult<Unit>
    public suspend fun resumeRecording(): CameraKResult<Unit>

    /**
     * Terminal, non-suspending, idempotent. Finalizes an active recording first (rule 7). Pending
     * calls complete with `Failure(Closed)`.
     */
    public fun close()

    /** [close], then waits until the native resources are released. */
    public suspend fun closeAndJoin()
}

/** Builds the engine a holder runs on. */
@CameraKEngineApi
public fun interface CameraEngineFactory {
    public fun create(config: CameraConfiguration, environment: CameraKEnvironment): CameraEngine
}

/** Closed hierarchy: a platform-confirmed recording milestone. Subtypes compare by value. */
@CameraKEngineApi
public sealed interface EngineRecordingEvent {
    @Poko
    public class Started(public val path: String, public val hasAudio: Boolean) : EngineRecordingEvent

    public data object Paused : EngineRecordingEvent

    public data object Resumed : EngineRecordingEvent

    @Poko
    public class Finalized(public val result: CameraKResult<RecordedVideo>, public val reason: RecordingEndReason) :
        EngineRecordingEvent
}
