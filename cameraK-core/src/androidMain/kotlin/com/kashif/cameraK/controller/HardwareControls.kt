package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKLogger

private const val TAG = "CameraK"

/**
 * Zoom, torch and flash: what the configuration or the user asked for, applied to the bound camera
 * and capture, and re-applied to every camera a bind returns.
 */
internal class HardwareControls(
    private val context: Context,
    flashMode: FlashMode,
    torchMode: TorchMode,
    private val camera: () -> Camera?,
    private val imageCapture: () -> ImageCapture?,
) {
    var flashMode: FlashMode = flashMode
        private set

    var torchMode: TorchMode = torchMode
        private set

    // The zoom ratio the user last asked for; null until they do. Re-applied after every bind,
    // because each bind gives CameraX a camera at 1x.
    @Volatile
    private var desiredZoom: Float? = null

    fun toggleFlashMode() {
        setFlashMode(
            when (flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            },
        )
    }

    fun setFlashMode(mode: FlashMode) {
        flashMode = mode
        imageCapture()?.flashMode = mode.toCameraXFlashMode()
    }

    /** The flash mode of the bound capture, or null before a bind. */
    fun boundFlashMode(): FlashMode? = when (imageCapture()?.flashMode) {
        ImageCapture.FLASH_MODE_ON -> FlashMode.ON
        ImageCapture.FLASH_MODE_OFF -> FlashMode.OFF
        ImageCapture.FLASH_MODE_AUTO -> FlashMode.AUTO
        else -> null
    }

    fun toggleTorchMode() {
        setTorchMode(
            when (torchMode) {
                TorchMode.OFF -> TorchMode.ON
                TorchMode.ON -> TorchMode.AUTO
                TorchMode.AUTO -> TorchMode.OFF
            },
        )
    }

    fun setTorchMode(mode: TorchMode) {
        torchMode = mode
        if (mode == TorchMode.AUTO) {
            CameraKLogger.w(TAG, "TorchMode.AUTO not natively supported, using ON")
        }
        camera()?.let { applyTorch(it) }
    }

    fun setZoom(zoomRatio: Float) {
        desiredZoom = zoomRatio
        camera()?.let { applyZoom(it, zoomRatio) }
    }

    fun getZoom(): Float = camera()?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun getMaxZoom(): Float = camera()?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /**
     * Applies the torch and zoom the configuration or the user asked for to a freshly bound camera:
     * CameraX opens every bound camera with the torch off and no zoom.
     */
    fun applyDesiredHardwareState(camera: Camera) {
        if (torchMode != TorchMode.OFF) applyTorch(camera)
        desiredZoom?.let { applyZoom(camera, it) }
    }

    /** Asks [camera] for [torchMode], if it has a flash unit; CameraX has no AUTO torch, so AUTO is on. */
    private fun applyTorch(camera: Camera) {
        val mode = torchMode
        val on = mode != TorchMode.OFF
        if (!camera.cameraInfo.hasFlashUnit()) {
            if (on) CameraKLogger.w(TAG, "TorchMode.$mode requested, but this camera has no flash unit")
            return
        }
        logIfFailed(if (on) "Turning the torch on" else "Turning the torch off") {
            camera.cameraControl.enableTorch(on)
        }
    }

    /** Asks [camera] for [zoom], clamped to the range it reports, which can start below 1x. */
    private fun applyZoom(camera: Camera, zoom: Float) {
        val state = camera.cameraInfo.zoomState.value
        val ratio = state?.let { zoom.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: zoom
        logIfFailed("Setting the zoom") { camera.cameraControl.setZoomRatio(ratio) }
    }

    /** Logs the failure of a CameraX control call instead of dropping its future. */
    private fun logIfFailed(operation: String, call: () -> ListenableFuture<*>) {
        val future = try {
            call()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "$operation failed", e)
            return
        }
        future.addListener({
            try {
                future.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause ?: e
                // CameraX cancels a pending request when a newer one replaces it, as in a pinch.
                if (cause is CameraControl.OperationCanceledException) {
                    CameraKLogger.d(TAG, "$operation was superseded")
                } else {
                    CameraKLogger.w(TAG, "$operation failed", cause)
                }
            } catch (e: java.util.concurrent.CancellationException) {
                CameraKLogger.d(TAG, "$operation was cancelled")
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

internal fun FlashMode.toCameraXFlashMode(): Int = when (this) {
    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
}
