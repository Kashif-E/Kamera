@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The hardware [AndroidCameraEngine] reports and the commands that change it (design §2.3 rule 5,
 * §6.2). [hardware] and [zoom] are what CameraX reports: torch and zoom from the bound camera's
 * `torchState` and `zoomState`, flash from a read-back of the bound ImageCapture after each set and
 * each bind. The desired flash, torch and zoom are [HardwareControls]'s, applied once the camera
 * opens and after every bind made while it runs. The target orientation sets the capture rotation of
 * the bound ImageCapture and VideoCapture. Main thread.
 */
internal class EngineControls(
    context: Context,
    flashMode: FlashMode,
    torchMode: TorchMode,
    backend: CameraXBackend,
    private val orientation: OrientationTracker,
    private val binder: UseCaseBinder,
    private val stillCapturer: StillCapturer,
    private val videoRecorder: VideoRecorder,
) {
    private val hardwareControls = HardwareControls(
        context = context,
        flashMode = flashMode,
        torchMode = torchMode,
        camera = { binder.camera },
        imageCapture = { stillCapturer.imageCapture },
        readFlash = backend::flashModeOf,
    )

    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    /** The flash mode every ImageCapture a bind builds is given. */
    val flashMode: FlashMode get() = hardwareControls.flashMode

    fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
        if (mode != FlashMode.OFF && hardwareControls.hasFlashUnit() != true) {
            val feature = if (mode == FlashMode.AUTO) CameraFeature.FLASH_AUTO else CameraFeature.FLASH_ON
            return CameraKResult.Failure(CameraKError.Unsupported(feature))
        }
        // No observer reports flash: the bound ImageCapture's mode, read back, is the report.
        val readBack = hardwareControls.setFlashMode(mode) ?: return CameraKResult.Failure(
            CameraKError.Internal(IllegalStateException("an open camera has no ImageCapture")),
        )
        publishHardware(flashMode = readBack)
        return CameraKResult.Success(readBack)
    }

    fun setZoom(ratio: Float): CameraKResult<ZoomState> {
        val range = hardwareControls.reportedZoomRange()
            ?: _hardware.value.camera?.let { it.minZoom..it.maxZoom }
            ?: 1f..1f
        val min = range.start
        val max = range.endInclusive
        return when {
            // A fixed zoom has nothing to clamp to: its one ratio, or nothing.
            min >= max && ratio != min -> CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.ZOOM))
            else -> {
                // CameraX fails setZoomRatio out of range; a pinch overshoots its ends, so clamp.
                val clamped = ratio.coerceIn(min, max)
                hardwareControls.setZoom(clamped)
                CameraKResult.Success(ZoomState(clamped, min, max))
            }
        }
    }

    fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = when {
        mode == TorchMode.AUTO -> CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.TORCH))
        mode == TorchMode.ON && hardwareControls.hasFlashUnit() != true ->
            CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.TORCH))
        else -> {
            // hardware follows CameraX's torchState, not this request.
            hardwareControls.setTorchMode(mode)
            CameraKResult.Success(mode)
        }
    }

    // Stored only: the camera's control is inactive until it opens, which applies them. A start that
    // names no target orientation keeps the one stored while the engine was closed or stopped.
    fun store(desired: DesiredHardware) {
        hardwareControls.store(desired.flashMode, desired.torchMode, desired.zoomRatio)
        desired.targetOrientation?.let { orientation.targetOrientation = it }
    }

    /**
     * Sets the one target orientation captures follow, applies it to the bound outputs and, while
     * [open], reports it; before Open the next bind applies it and the open reports it.
     */
    fun applyTargetOrientation(target: DeviceOrientation?, open: Boolean) {
        orientation.targetOrientation = target
        if (binder.camera != null) applyCaptureRotation()
        if (open) publishHardware(targetOrientation = target)
    }

    /**
     * Sets the capture rotation from the current display rotation (auto mode) or the explicit target
     * orientation. Keeping capture rotation tied to the display — the same source as the ViewPort and
     * the preview box — guarantees the saved photo's orientation matches what the user saw (#136).
     */
    fun applyCaptureRotation() {
        val override = orientation.targetOrientation
        applyRotation(override?.toSurfaceRotation() ?: binder.displayRotation())
    }

    private fun applyRotation(rotation: Int) {
        stillCapturer.imageCapture?.targetRotation = rotation
        videoRecorder.videoCapture?.targetRotation = rotation
    }

    /**
     * A bind of [camera] succeeded: flash is checked against the bound camera, and [hardware] reports
     * the bound ImageCapture's flash, CameraX's torch, the bound [lens] and the target orientation.
     */
    fun onBound(camera: Camera, lens: () -> LensInfo?) {
        hardwareControls.revalidateFlash()
        _hardware.value = HardwareState(
            flashMode = hardwareControls.boundFlashMode() ?: hardwareControls.flashMode,
            torchMode = if (camera.cameraInfo.torchState.value == TorchState.ON) TorchMode.ON else TorchMode.OFF,
            camera = lens(),
            targetOrientation = orientation.targetOrientation,
        )
    }

    /** Applies the desired flash, torch and zoom to [camera], whose control is active. */
    fun applyDesiredTo(camera: Camera) {
        hardwareControls.applyDesiredHardwareState(camera)
    }

    /** [camera] opened: its control is active from here, so what is desired is applied and reported. */
    fun onOpened(camera: Camera) {
        hardwareControls.applyDesiredHardwareState(camera)
        publishHardware(
            flashMode = hardwareControls.boundFlashMode() ?: _hardware.value.flashMode,
            targetOrientation = orientation.targetOrientation,
        )
    }

    fun reportTorch(torch: Int) {
        publishHardware(torchMode = if (torch == TorchState.ON) TorchMode.ON else TorchMode.OFF)
    }

    fun reportZoom(zoom: ZoomState) {
        _zoom.value = zoom
    }

    fun reportDeviceOrientation(orientation: DeviceOrientation) {
        _deviceOrientation.value = orientation
    }

    private fun publishHardware(
        flashMode: FlashMode = _hardware.value.flashMode,
        torchMode: TorchMode = _hardware.value.torchMode,
        targetOrientation: DeviceOrientation? = _hardware.value.targetOrientation,
    ) {
        _hardware.value = HardwareState(flashMode, torchMode, _hardware.value.camera, targetOrientation)
    }
}
