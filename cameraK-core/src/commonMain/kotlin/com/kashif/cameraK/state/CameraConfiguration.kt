package com.kashif.cameraK.state

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat

/**
 * Configuration for camera initialization.
 * Immutable by design to ensure predictable camera setup.
 *
 * Use this to configure the camera before initialization.
 * Changes to configuration require camera reinitialization.
 *
 * @property flashMode Initial flash mode.
 * @property torchMode Initial torch mode.
 * @property cameraLens Initial camera lens.
 * @property imageFormat Image format for captures.
 * @property qualityPrioritization Quality vs performance trade-off.
 * @property cameraDeviceType Preferred camera device type.
 * @property aspectRatio Aspect ratio for preview and capture.
 * @property targetResolution Target resolution (width x height) or null for auto.
 * @property directory Kept for 1.x source compatibility. Captures and recordings are always app-owned
 *   files (see [outputDirectory]); [Directory.PICTURES] and [Directory.DCIM] are ignored with a logged
 *   warning. Publish to the gallery with ImageSaverPlugin.
 * @property mirrorFrontCamera If true, front-camera captures are horizontally mirrored to match
 *   the mirrored preview (selfie "what you see is what you get"). No effect on the back camera.
 *   Representation note: iOS and the Android byte-array path bake the flip into the pixels; the
 *   Android file path (`takePictureToFile`, the fast path) records it as an EXIF orientation tag,
 *   which EXIF-aware viewers honor but raw-pixel consumers may ignore.
 * @property cameraId A `LensInfo.id` from the capabilities. When set it wins over [cameraLens] and
 *   [cameraDeviceType]. Null selects by lens and device type, as 1.x does.
 * @property outputDirectory An app-owned directory for stills and, by default, recordings. Null is
 *   the platform's app-owned default.
 * @property frameAnalysis Bind the frame-analysis output at start. Set automatically when a plugin
 *   that needs frames is attached at creation; a later registration binds it once.
 * @property analysisResolution The size of analysis frames. Null follows the preview's aspect ratio.
 * @property analysisFormat The pixel format analysis frames arrive in, applied at bind.
 *
 * The 2.0 engines read the last five fields; the 1.x controllers do not.
 *
 * @example
 * ```kotlin
 * val config = CameraConfiguration(
 *     flashMode = FlashMode.AUTO,
 *     cameraLens = CameraLens.BACK,
 *     imageFormat = ImageFormat.JPEG,
 *     qualityPrioritization = QualityPrioritization.QUALITY,
 *     aspectRatio = AspectRatio.RATIO_16_9
 * )
 * val cameraState by rememberCameraKState(config)
 * ```
 */
data class CameraConfiguration(
    val flashMode: FlashMode = FlashMode.OFF,
    val torchMode: TorchMode = TorchMode.OFF,
    val cameraLens: CameraLens = CameraLens.BACK,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED,
    val cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val targetResolution: Pair<Int, Int>? = null,
    val directory: Directory = Directory.DOCUMENTS,
    val mirrorFrontCamera: Boolean = false,
    val cameraId: String? = null,
    val outputDirectory: String? = null,
    val frameAnalysis: Boolean = false,
    val analysisResolution: Resolution? = null,
    val analysisFormat: FrameFormat = FrameFormat.YUV_420,
) {
    // CF' red stubs: the DSL's new fields and its builder, declared so CF''s tests compile.
    val initialZoomRatio: Float get() = TODO("CF'")
    val targetOrientation: DeviceOrientation? get() = TODO("CF'")

    class Builder internal constructor() {
        var cameraLens: CameraLens? = null
        var cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT
        var cameraId: String? = null
        var flashMode: FlashMode = FlashMode.OFF
        var torchMode: TorchMode = TorchMode.OFF
        var initialZoomRatio: Float = 1f
        var imageFormat: ImageFormat = ImageFormat.JPEG
        var qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED
        var aspectRatio: AspectRatio = AspectRatio.RATIO_16_9
        var targetResolution: Resolution? = null
        var targetOrientation: DeviceOrientation? = null
        var outputDirectory: String? = null
        var mirrorFrontCamera: Boolean = false
        var frameAnalysis: Boolean = false
        var analysisResolution: Resolution? = null
        var analysisFormat: FrameFormat = FrameFormat.YUV_420
    }
}

/** CF' red stub. */
fun CameraConfiguration(block: CameraConfiguration.Builder.() -> Unit): CameraConfiguration = TODO("CF'")

/** CF' red stub. */
fun CameraConfiguration.copy(block: CameraConfiguration.Builder.() -> Unit): CameraConfiguration = TODO("CF'")

/** CF' red stub: the one defaults source. */
internal object CameraDefaults {
    val LENS: CameraLens? = null
    val DEVICE_TYPE = CameraDeviceType.DEFAULT
    val CAMERA_ID: String? = null
    val FLASH = FlashMode.OFF
    val TORCH = TorchMode.OFF
    const val ZOOM_RATIO = 1f
    val IMAGE_FORMAT = ImageFormat.JPEG
    val QUALITY = QualityPrioritization.BALANCED
    val ASPECT_RATIO = AspectRatio.RATIO_16_9
    val TARGET_RESOLUTION: Resolution? = null
    val TARGET_ORIENTATION: DeviceOrientation? = null
    val OUTPUT_DIRECTORY: String? = null
    const val MIRROR_FRONT_CAMERA = false
    const val FRAME_ANALYSIS = false
    val ANALYSIS_RESOLUTION: Resolution? = null
    val ANALYSIS_FORMAT = FrameFormat.YUV_420
}
