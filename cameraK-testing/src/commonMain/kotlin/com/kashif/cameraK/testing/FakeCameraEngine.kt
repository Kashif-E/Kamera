@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** S2' red: the scripting surface only. */
public class FakeCameraEngine(
    capabilities: CameraCapabilities = FakeCapabilities.backAndFront(),
    initialFlash: FlashMode = FlashMode.OFF,
    initialTorch: TorchMode = TorchMode.OFF,
    initialZoom: Float = 1f,
) : CameraEngine {
    override val state: StateFlow<EngineState> get() = TODO("S2' red")
    override val capabilities: StateFlow<CameraCapabilities> get() = TODO("S2' red")
    override val hardware: StateFlow<HardwareState> get() = TODO("S2' red")
    override val zoom: StateFlow<ZoomState> get() = TODO("S2' red")
    override val deviceOrientation: StateFlow<DeviceOrientation> get() = TODO("S2' red")
    override val recordingEvents: Flow<EngineRecordingEvent> get() = TODO("S2' red")
    public val fakeFrames: FakeFrameSource get() = TODO("S2' red")
    override val frames: FrameSource get() = TODO("S2' red")
    override val preview: PlatformPreviewSource get() = TODO("S2' red")
    public val calls: List<String> get() = TODO("S2' red")
    public val startCalls: Int get() = TODO("S2' red")
    public val stopCalls: Int get() = TODO("S2' red")
    public val closeCalls: Int get() = TODO("S2' red")
    public val setZoomCalls: Int get() = TODO("S2' red")
    public var autoOpen: Boolean
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")
    public var configuration: CameraConfiguration
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")
    public var captureResult: suspend () -> CameraKResult<CapturedImage>
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")
    public var supportsPause: Boolean
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")
    public var autoStartRecording: Boolean
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")
    public var autoFinalizeRecording: Boolean
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")
    public val appliedFlashMode: FlashMode get() = TODO("S2' red")
    public val appliedTorchMode: TorchMode get() = TODO("S2' red")
    public val appliedZoomRatio: Float get() = TODO("S2' red")
    public val appliedCameraId: String? get() = TODO("S2' red")
    public val releaseCount: Int get() = TODO("S2' red")

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> = TODO("S2' red")

    override suspend fun stop(): Unit = TODO("S2' red")

    override fun close(): Unit = TODO("S2' red")

    override suspend fun closeAndJoin(): Unit = TODO("S2' red")

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

    public fun deliverOpen(): Unit = TODO("S2' red")

    public fun failOpen(error: CameraKError): Unit = TODO("S2' red $error")

    public fun startReturnsWithoutPublishing(error: CameraKError): Unit = TODO("S2' red $error")

    public fun interrupt(reason: InterruptionReason): Unit = TODO("S2' red $reason")

    public fun endInterruption(): Unit = TODO("S2' red")

    public fun fail(error: CameraKError): Unit = TODO("S2' red $error")

    public fun reopenAfterFailure(): Unit = TODO("S2' red")

    public fun holdNextCapture(): Unit = TODO("S2' red")

    public fun releaseCapture(): Unit = TODO("S2' red")

    public fun finishRecording(result: CameraKResult<RecordedVideo>, reason: RecordingEndReason): Unit =
        TODO("S2' red $result $reason")

    public fun deliverRecordingStarted(): Unit = TODO("S2' red")

    public fun deliverRecordingFinalized(): Unit = TODO("S2' red")

    public fun throwFromNextCallback(): Unit = TODO("S2' red")

    public fun reportFlash(mode: FlashMode): Unit = TODO("S2' red $mode")

    public fun reportTorch(mode: TorchMode): Unit = TODO("S2' red $mode")

    public fun reportZoom(ratio: Float, minRatio: Float = 1f, maxRatio: Float = 10f): Unit =
        TODO("S2' red $ratio $minRatio $maxRatio")

    public fun reportCamera(lensId: String): Unit = TODO("S2' red $lensId")
}
