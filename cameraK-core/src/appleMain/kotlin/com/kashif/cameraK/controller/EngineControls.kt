@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

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
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/**
 * An iOS engine's flash, torch and zoom (contract rule 5): the commands, the zoom and torch the engine
 * re-applies after each start and switch, and [hardware] and [zoom] as the device reports them by
 * key-value observing, the flash as the photo output reads it back. The observed device and its
 * observation are touched on the session queue only.
 */
internal class EngineControls(
    private val generations: EngineGenerations,
    private val capabilities: EngineCapabilities,
    private val custom: CustomCameraController,
    private val targetOrientation: () -> DeviceOrientation?,
) {
    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    /** The zoom ratio the engine re-applies after each start and camera switch. */
    @Volatile
    var desiredZoom = 1f
        private set

    /** The torch the engine applies at start and after each camera switch. */
    @Volatile
    var desiredTorch = TorchMode.OFF
        private set

    /** The device whose torch and zoom the engine observes. Session queue only. */
    var observedDevice: CaptureDeviceOps? = null
        private set
    private var hardwareObservation: AutoCloseable? = null

    /** Session queue: the zoom, torch and flash a start asks for, stored for the camera to take. */
    fun takeDesired(desired: DesiredHardware) {
        desiredZoom = desired.zoomRatio
        desiredTorch = desired.torchMode
        custom.setFlashMode(desired.flashMode.toAVCaptureFlashMode())
        custom.torchMode = desiredTorch.toAVCaptureTorchMode()
    }

    /**
     * Validates [mode] against the camera's flash modes, stores it on the session queue, and returns
     * and publishes the mode the photo output will use, read back after the store (contract rule 5).
     */
    suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
        generations.notOpen()?.let { return it }
        val camera = custom.currentDevice?.let(capabilities::lensInfo)
        if (camera != null && mode !in capabilities.capabilities.value.supportedFlashModes(camera.id)) {
            val feature = if (mode == FlashMode.AUTO) CameraFeature.FLASH_AUTO else CameraFeature.FLASH_ON
            return engineFailure(CameraKError.Unsupported(feature))
        }
        return generations.onSessionQueue("setting the flash mode") {
            custom.setFlashMode(mode.toAVCaptureFlashMode())
            publishHardware()
            CameraKResult.Success(custom.captureFlashMode.toFlashMode())
        }
    }

    /**
     * Clamps [ratio] into the camera's available zoom range on the session queue before the device sees
     * it, since AVFoundation raises an exception nothing can catch beyond the format's range and clamps
     * silently to the available one ([CaptureDeviceOps.minVideoZoomFactor]), and returns the clamped
     * ratio without waiting for the device's report. A camera with a fixed zoom takes only that ratio.
     */
    suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> {
        generations.notOpen()?.let { return it }
        if (ratio.isNaN()) return engineFailure(CameraKError.Unsupported(CameraFeature.ZOOM))
        return generations.onSessionQueue("setting the zoom") {
            val device = custom.currentDevice ?: return@onSessionQueue engineFailure(CameraKError.NotReady)
            val min = device.minVideoZoomFactor
            val max = device.maxAvailableVideoZoomFactor
            if (max <= min) {
                return@onSessionQueue if (ratio.toDouble() == min) {
                    CameraKResult.Success(ZoomState(min.toFloat(), min.toFloat(), max.toFloat()))
                } else {
                    engineFailure(CameraKError.Unsupported(CameraFeature.ZOOM))
                }
            }
            val factor = ratio.toDouble().coerceIn(min, max)
            custom.setZoomOnQueue(device, factor)?.let { return@onSessionQueue hardwareFailure("setting the zoom", it) }
            desiredZoom = factor.toFloat()
            CameraKResult.Success(ZoomState(factor.toFloat(), min.toFloat(), max.toFloat()))
        }
    }

    /**
     * Sets the torch on the session queue when the camera's device supports [mode], the source of its
     * torch capabilities; [hardware] follows when the device reports it lit or dark.
     */
    suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> {
        generations.notOpen()?.let { return it }
        return generations.onSessionQueue("setting the torch") {
            val device = custom.currentDevice ?: return@onSessionQueue engineFailure(CameraKError.NotReady)
            val avMode = mode.toAVCaptureTorchMode()
            if (!device.hasTorch || !device.isTorchModeSupported(avMode)) {
                return@onSessionQueue engineFailure(CameraKError.Unsupported(CameraFeature.TORCH))
            }
            custom.setTorchOnQueue(device, avMode)?.let {
                return@onSessionQueue hardwareFailure("setting the torch", it)
            }
            desiredTorch = mode
            custom.torchMode = avMode
            CameraKResult.Success(mode)
        }
    }

    private fun hardwareFailure(what: String, cause: CustomCameraController.CameraException): CameraKResult.Failure {
        CameraKLogger.e(TAG, "$what failed", cause)
        return engineFailure(engineErrorFor(cause, null))
    }

    /** Session queue: [device]'s torch and zoom observed in place of the previous camera's. */
    fun observe(device: CaptureDeviceOps) {
        if (observedDevice === device) return
        stopObservingHardware()
        observedDevice = device
        hardwareObservation = device.observeHardware {
            platformCallback("a torch or zoom report") {
                custom.afterQueuedSessionWork {
                    platformCallback("a torch or zoom report") { onHardwareReport() }
                }
            }
        }
    }

    // Session queue only.
    fun stopObservingHardware() {
        hardwareObservation?.close()
        hardwareObservation = null
        observedDevice = null
    }

    /**
     * Session queue: a key-value report, published while a generation is starting or live. A report from
     * a camera switched away from, queued before its observation closed, publishes the live camera's
     * values again, which [publishHardware] reads from the device in use, so it changes nothing.
     */
    private fun onHardwareReport() {
        if (!generations.isStartingOrLive) return
        publishHardware()
    }

    /** Session queue: the desired zoom, clamped into [device]'s range, unless the device reports it already. */
    fun applyDesiredZoom(device: CaptureDeviceOps) {
        val min = device.minVideoZoomFactor
        val max = device.maxAvailableVideoZoomFactor
        if (max < min) return
        val factor = desiredZoom.toDouble().coerceIn(min, max)
        if (device.videoZoomFactor == factor) return
        custom.setZoomOnQueue(device, factor)?.let { failure ->
            CameraKLogger.e(TAG, "re-applying the zoom failed", failure)
        }
    }

    /**
     * Session queue: the camera, torch and zoom as the device reports them, the flash the photo output
     * will use, and the target orientation.
     */
    fun publishHardware() {
        val device = custom.currentDevice ?: return
        if (generations.isClosed) return
        _hardware.value = HardwareState(
            flashMode = custom.captureFlashMode.toFlashMode(),
            torchMode = when {
                device.torchMode == AVCaptureTorchModeAuto -> TorchMode.AUTO
                // A torch the system shut off for heat keeps its mode but is dark.
                device.torchMode == AVCaptureTorchModeOn && device.isTorchActive -> TorchMode.ON
                else -> TorchMode.OFF
            },
            camera = capabilities.lensInfo(device),
            targetOrientation = targetOrientation(),
        )
        _zoom.value = ZoomState(
            device.videoZoomFactor.toFloat(),
            device.minVideoZoomFactor.toFloat(),
            device.maxAvailableVideoZoomFactor.toFloat(),
        )
    }
}
