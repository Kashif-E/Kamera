@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Migration only, until C1': the channel that carries the 1.x facade to `Ready.controller`. */
internal interface LegacyControllerAccess {
    /** One facade per engine, created with it. */
    val legacyController: CameraController
}

/** A 1.x controller's session calls. Each runs on the thread its platform's 1.x code expects. */
internal interface LegacySession {
    suspend fun start()

    suspend fun stop()

    suspend fun close()

    /** Runs [block], a read of the 1.x controller, on that same thread. */
    suspend fun <T> onSessionThread(block: () -> T): T
}

/** S2' red: the declaration only. */
internal class LegacyControllerEngine(
    override val legacyController: CameraController,
    override val preview: PlatformPreviewSource,
    private val session: LegacySession,
    private val environment: CameraKEnvironment,
) : CameraEngine,
    LegacyControllerAccess {
    override val state: StateFlow<EngineState> get() = TODO("S2' red")
    override val hardware: StateFlow<HardwareState> get() = TODO("S2' red")
    override val zoom: StateFlow<ZoomState> get() = TODO("S2' red")
    override val deviceOrientation: StateFlow<DeviceOrientation> get() = TODO("S2' red")
    override val capabilities: StateFlow<CameraCapabilities> get() = TODO("S2' red")
    override val recordingEvents: Flow<EngineRecordingEvent> get() = TODO("S2' red")
    override val frames: FrameSource get() = TODO("S2' red")

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = TODO("S2' red")

    override suspend fun stop(): Unit = TODO("S2' red")

    override suspend fun capture(): CameraKResult<CapturedImage> = TODO("S2' red")

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = TODO("S2' red")

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = TODO("S2' red")

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = TODO("S2' red")

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> = TODO("S2' red")

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> = TODO("S2' red")

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> = TODO("S2' red")

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> = TODO("S2' red")

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = TODO("S2' red")

    override suspend fun pauseRecording(): CameraKResult<Unit> = TODO("S2' red")

    override suspend fun resumeRecording(): CameraKResult<Unit> = TODO("S2' red")

    override fun close(): Unit = TODO("S2' red")

    override suspend fun closeAndJoin(): Unit = TODO("S2' red")
}
