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

/** The iOS engine: an AVCaptureSession behind the engine contract. Stub until I1''s fix. */
internal class AppleCameraEngine(
    private val config: CameraConfiguration,
    private val environment: CameraKEnvironment,
    internal val custom: CustomCameraController,
    private val enumerateLenses: () -> List<LensInfo> = custom::getCameraCapabilities,
) : CameraEngine,
    LegacyControllerAccess {
    override val legacyController: CameraController get() = TODO("I1'")
    override val state: StateFlow<EngineState> get() = TODO("I1'")
    override val hardware: StateFlow<HardwareState> get() = TODO("I1'")
    override val zoom: StateFlow<ZoomState> get() = TODO("I1'")
    override val deviceOrientation: StateFlow<DeviceOrientation> get() = TODO("I1'")
    override val capabilities: StateFlow<CameraCapabilities> get() = TODO("I1'")
    override val recordingEvents: Flow<EngineRecordingEvent> get() = TODO("I1'")
    override val frames: FrameSource get() = TODO("I1'")
    override val preview: PlatformPreviewSource get() = TODO("I1'")

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = TODO("I1'")

    override suspend fun stop(): Unit = TODO("I1'")

    override suspend fun capture(): CameraKResult<CapturedImage> = TODO("I1'")

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = TODO("I1'")

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = TODO("I1'")

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = TODO("I1'")

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> = TODO("I1'")

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> = TODO("I1'")

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> = TODO("I1'")

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> = TODO("I1'")

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = TODO("I1'")

    override suspend fun pauseRecording(): CameraKResult<Unit> = TODO("I1'")

    override suspend fun resumeRecording(): CameraKResult<Unit> = TODO("I1'")

    override fun close(): Unit = TODO("I1'")

    override suspend fun closeAndJoin(): Unit = TODO("I1'")
}
