@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** No frame for this long after Open interrupts the session (§2.4 desktop). */
internal val NO_FRAME_TIMEOUT: Duration = 5.seconds

/** An interrupted session that has not delivered a frame again within this long is Disconnected. */
internal val RECOVERY_TIMEOUT: Duration = 10.seconds

/** The desktop engine over JavaCV (§2.4). */
internal class DesktopCameraEngine(
    private val config: CameraConfiguration,
    private val environment: CameraKEnvironment,
    private val source: GrabberSource,
    private val authorization: CameraAuthorization = CameraAuthorization { TODO("D1'") },
    private val os: OperatingSystem = OperatingSystem.LINUX,
    nanoClock: () -> Long = System::nanoTime,
    horizontalFlip: Boolean = false,
    private val listenerWrapper: (GrabLoopListener) -> GrabLoopListener = { it },
    private val enumerate: () -> CameraCapabilities = ::desktopCapabilities,
    facade: (DesktopCameraEngine) -> CameraController = { TODO("D1'") },
) : CameraEngine,
    LegacyControllerAccess {
    init {
        TODO("D1'")
    }

    internal val grabber: CameraGrabber get() = TODO("D1'")
    override val legacyController: CameraController get() = TODO("D1'")
    override val state: StateFlow<EngineState> get() = TODO("D1'")
    override val hardware: StateFlow<HardwareState> get() = TODO("D1'")
    override val zoom: StateFlow<ZoomState> get() = TODO("D1'")
    override val deviceOrientation: StateFlow<DeviceOrientation> get() = TODO("D1'")
    override val capabilities: StateFlow<CameraCapabilities> get() = TODO("D1'")
    override val recordingEvents: Flow<EngineRecordingEvent> get() = TODO("D1'")
    override val frames: FrameSource get() = TODO("D1'")
    override val preview: PlatformPreviewSource get() = TODO("D1'")

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = TODO("D1'")

    override suspend fun stop(): Unit = TODO("D1'")

    override suspend fun capture(): CameraKResult<CapturedImage> = TODO("D1'")

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = TODO("D1'")

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = TODO("D1'")

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = TODO("D1'")

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> = TODO("D1'")

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> = TODO("D1'")

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> = TODO("D1'")

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> = TODO("D1'")

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = TODO("D1'")

    override suspend fun pauseRecording(): CameraKResult<Unit> = TODO("D1'")

    override suspend fun resumeRecording(): CameraKResult<Unit> = TODO("D1'")

    override fun close(): Unit = TODO("D1'")

    override suspend fun closeAndJoin(): Unit = TODO("D1'")
}

/** The 1.x enumerator's one camera, with the first real values of §6.3. */
internal fun desktopCapabilities(): CameraCapabilities = TODO("D1'")
