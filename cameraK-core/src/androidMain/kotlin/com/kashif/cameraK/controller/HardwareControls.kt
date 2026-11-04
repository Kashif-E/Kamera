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
 * The engine's desired zoom, torch and flash, and the CameraX calls that apply them. Flash is a
 * setting of the bound ImageCapture, given to every one a bind builds and read back through
 * [readFlash]. Torch and zoom go to the camera's control, which CameraX accepts only while the
 * camera's use cases are attached: the engine applies them once the camera is open, and again after
 * every bind made while it runs.
 */
internal class HardwareControls(
    private val context: Context,
    flashMode: FlashMode,
    torchMode: TorchMode,
    private val camera: () -> Camera?,
    private val imageCapture: () -> ImageCapture?,
    private val readFlash: (ImageCapture) -> Int,
) {
    var flashMode: FlashMode = flashMode
        private set

    var torchMode: TorchMode = torchMode
        private set

    // Each bind gives CameraX a camera at 1x, so 1x needs no request.
    @Volatile
    var zoomRatio: Float = 1f
        private set

    /** Stores what the next open or bind applies, without asking the camera for anything. */
    fun store(
        flashMode: FlashMode = this.flashMode,
        torchMode: TorchMode = this.torchMode,
        zoomRatio: Float = this.zoomRatio,
    ) {
        this.flashMode = flashMode
        this.torchMode = torchMode
        this.zoomRatio = zoomRatio
        // An ImageCapture kept across a stop is the one the next start uses.
        revalidateFlash()
    }

    /** Sets [mode] on the bound ImageCapture and returns its read-back, or null before a bind. */
    fun setFlashMode(mode: FlashMode): FlashMode? {
        flashMode = mode
        revalidateFlash()
        return boundFlashMode()
    }

    /**
     * Gives the bound ImageCapture the desired flash, validated against the bound camera (design
     * §6.2): a camera without a flash unit takes FLASH_MODE_OFF, and the desired mode is kept for the
     * next camera that has one. Each bind calls this once CameraX has returned the camera.
     */
    fun revalidateFlash() {
        val capture = imageCapture() ?: return
        capture.flashMode = if (hasFlashUnit() == false) ImageCapture.FLASH_MODE_OFF else flashMode.toCameraXFlashMode()
    }

    /** The flash mode the bound ImageCapture reports, or null before a bind. */
    fun boundFlashMode(): FlashMode? = imageCapture()?.let { fromCameraXFlashMode(readFlash(it)) }

    fun setTorchMode(mode: TorchMode) {
        torchMode = mode
        camera()?.let { applyTorch(it) }
    }

    fun setZoom(zoomRatio: Float) {
        this.zoomRatio = zoomRatio
        camera()?.let { applyZoom(it, zoomRatio) }
    }

    fun getZoom(): Float = camera()?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun getMaxZoom(): Float = camera()?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /** Whether the bound camera has a flash unit, as CameraX reports it; null before a bind. */
    fun hasFlashUnit(): Boolean? = camera()?.cameraInfo?.hasFlashUnit()

    /** The zoom range the bound camera reports, or null before a bind. */
    fun reportedZoomRange(): ClosedFloatingPointRange<Float>? =
        camera()?.cameraInfo?.zoomState?.value?.let { it.minZoomRatio..it.maxZoomRatio }

    /**
     * Applies the desired torch and zoom to [camera], whose control has just become active: CameraX
     * opens every camera with the torch off and at 1x, so only other values need a request.
     */
    fun applyDesiredHardwareState(camera: Camera) {
        if (torchMode != TorchMode.OFF) applyTorch(camera)
        if (zoomRatio != 1f) applyZoom(camera, zoomRatio)
    }

    /** Asks [camera] for [torchMode], if it has a flash unit. CameraX has no AUTO torch. */
    private fun applyTorch(camera: Camera) {
        val mode = torchMode
        if (mode == TorchMode.AUTO) {
            CameraKLogger.w(TAG, "TorchMode.AUTO is not supported on Android; the torch stays off")
            return
        }
        val on = mode == TorchMode.ON
        if (!camera.cameraInfo.hasFlashUnit()) {
            if (on) CameraKLogger.w(TAG, "TorchMode.ON requested, but this camera has no flash unit")
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

// FLASH_MODE_SCREEN, the front-screen flash, is not a mode CameraK sets; it reads as OFF.
private fun fromCameraXFlashMode(mode: Int): FlashMode = when (mode) {
    ImageCapture.FLASH_MODE_ON -> FlashMode.ON
    ImageCapture.FLASH_MODE_AUTO -> FlashMode.AUTO
    else -> FlashMode.OFF
}
