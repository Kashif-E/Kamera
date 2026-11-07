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
 * Configuration for camera initialization, built with the `CameraConfiguration { }` DSL.
 *
 * Immutable; [copy] builds a changed copy. Every field the block leaves unset takes its default, so
 * `CameraConfiguration()` is the default configuration. A new configuration builds a new holder.
 * Engines apply every field on first bind; one a platform cannot honour is listed as unsupported
 * in the capabilities, and the start fails with `Unsupported`.
 *
 * @property cameraLens The lens to open. Null, the default, is the platform default camera: the BACK
 *   camera on Android and iOS, the system default device on desktop.
 * @property cameraDeviceType Preferred camera device type.
 * @property cameraId A `LensInfo.id` from the capabilities. When set it wins over [cameraLens] and
 *   [cameraDeviceType].
 * @property flashMode Initial flash mode.
 * @property torchMode Initial torch mode.
 * @property initialZoomRatio The zoom the camera opens at, clamped into its range.
 * @property initialExposureCompensationIndex The exposure compensation index the camera opens at,
 *   clamped into its range; a camera without compensation ignores it.
 * @property imageFormat Image format for captures.
 * @property qualityPrioritization Quality vs performance trade-off.
 * @property aspectRatio Aspect ratio for preview and capture.
 * @property targetResolution Target resolution, or null for the platform's choice.
 * @property targetOrientation The orientation captures and recordings are fixed to; null follows the
 *   device.
 * @property outputDirectory The directory stills and, by default, recordings are written to, used as
 *   given. Null, the default, is the platform's app-owned storage (`filesDir/CameraK` on Android,
 *   `Documents/CameraK` on iOS, `Documents/CameraK` under the user's home on desktop); nothing checks
 *   that a directory given here is app-owned. Publish to the gallery with the saver plugin.
 * @property mirrorFrontCamera If true, front-camera captures are horizontally mirrored to match the
 *   mirrored preview (selfie "what you see is what you get"). No effect on the back camera.
 *   Representation note: iOS and the Android byte-array path bake the flip into the pixels; the
 *   Android file path records it as an EXIF orientation tag, which EXIF-aware viewers honor but
 *   raw-pixel consumers may ignore.
 * @property frameAnalysis Bind the frame-analysis output at start. Set automatically when a plugin
 *   that needs frames is attached at creation; a later registration binds it once.
 * @property analysisResolution The size analysis frames are asked for. Null follows the preview's
 *   aspect ratio. Android binds the nearest size the camera supports; desktop scales each frame to
 *   it, centre-cropped to its aspect ratio; on iOS it is a preference, and analysis frames come at the
 *   session's size, which their width and height state.
 * @property analysisFormat The pixel format analysis frames arrive in, applied at bind.
 * @throws IllegalArgumentException from the builder when [targetResolution] or [analysisResolution]
 *   has a zero or negative width or height.
 *
 * @sample com.kashif.cameraK.samples.cameraConfigurationSample
 */
public class CameraConfiguration private constructor(builder: Builder) {
    public val cameraLens: CameraLens? = builder.cameraLens
    public val cameraDeviceType: CameraDeviceType = builder.cameraDeviceType
    public val cameraId: String? = builder.cameraId
    public val flashMode: FlashMode = builder.flashMode
    public val torchMode: TorchMode = builder.torchMode
    public val initialZoomRatio: Float = builder.initialZoomRatio
    public val initialExposureCompensationIndex: Int = builder.initialExposureCompensationIndex
    public val imageFormat: ImageFormat = builder.imageFormat
    public val qualityPrioritization: QualityPrioritization = builder.qualityPrioritization
    public val aspectRatio: AspectRatio = builder.aspectRatio
    public val targetResolution: Resolution? = builder.targetResolution
    public val targetOrientation: DeviceOrientation? = builder.targetOrientation
    public val outputDirectory: String? = builder.outputDirectory
    public val mirrorFrontCamera: Boolean = builder.mirrorFrontCamera
    public val frameAnalysis: Boolean = builder.frameAnalysis
    public val analysisResolution: Resolution? = builder.analysisResolution
    public val analysisFormat: FrameFormat = builder.analysisFormat

    /** A copy with the fields [block] sets; every other field keeps this configuration's value. */
    public fun copy(block: Builder.() -> Unit): CameraConfiguration = Builder(this).apply(block).build()

    private fun fields(): List<Pair<String, Any?>> = listOf(
        "cameraLens" to cameraLens,
        "cameraDeviceType" to cameraDeviceType,
        "cameraId" to cameraId,
        "flashMode" to flashMode,
        "torchMode" to torchMode,
        "initialZoomRatio" to initialZoomRatio,
        "initialExposureCompensationIndex" to initialExposureCompensationIndex,
        "imageFormat" to imageFormat,
        "qualityPrioritization" to qualityPrioritization,
        "aspectRatio" to aspectRatio,
        "targetResolution" to targetResolution,
        "targetOrientation" to targetOrientation,
        "outputDirectory" to outputDirectory,
        "mirrorFrontCamera" to mirrorFrontCamera,
        "frameAnalysis" to frameAnalysis,
        "analysisResolution" to analysisResolution,
        "analysisFormat" to analysisFormat,
    )

    override fun equals(other: Any?): Boolean = other is CameraConfiguration && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        fields().joinToString(prefix = "CameraConfiguration(", postfix = ")") { (name, value) -> "$name=$value" }

    /** The DSL's receiver: one property per field, each starting at the default or at the copied value. */
    public class Builder internal constructor(from: CameraConfiguration?) {
        public var cameraLens: CameraLens? = if (from != null) from.cameraLens else CameraDefaults.LENS
        public var cameraDeviceType: CameraDeviceType = from?.cameraDeviceType ?: CameraDefaults.DEVICE_TYPE
        public var cameraId: String? = if (from != null) from.cameraId else CameraDefaults.CAMERA_ID
        public var flashMode: FlashMode = from?.flashMode ?: CameraDefaults.FLASH
        public var torchMode: TorchMode = from?.torchMode ?: CameraDefaults.TORCH
        public var initialZoomRatio: Float = from?.initialZoomRatio ?: CameraDefaults.ZOOM_RATIO
        public var initialExposureCompensationIndex: Int =
            from?.initialExposureCompensationIndex ?: CameraDefaults.EXPOSURE_COMPENSATION_INDEX
        public var imageFormat: ImageFormat = from?.imageFormat ?: CameraDefaults.IMAGE_FORMAT
        public var qualityPrioritization: QualityPrioritization =
            from?.qualityPrioritization ?: CameraDefaults.QUALITY
        public var aspectRatio: AspectRatio = from?.aspectRatio ?: CameraDefaults.ASPECT_RATIO
        public var targetResolution: Resolution? =
            if (from != null) from.targetResolution else CameraDefaults.TARGET_RESOLUTION
        public var targetOrientation: DeviceOrientation? =
            if (from != null) from.targetOrientation else CameraDefaults.TARGET_ORIENTATION
        public var outputDirectory: String? =
            if (from != null) from.outputDirectory else CameraDefaults.OUTPUT_DIRECTORY
        public var mirrorFrontCamera: Boolean = from?.mirrorFrontCamera ?: CameraDefaults.MIRROR_FRONT_CAMERA
        public var frameAnalysis: Boolean = from?.frameAnalysis ?: CameraDefaults.FRAME_ANALYSIS
        public var analysisResolution: Resolution? =
            if (from != null) from.analysisResolution else CameraDefaults.ANALYSIS_RESOLUTION
        public var analysisFormat: FrameFormat = from?.analysisFormat ?: CameraDefaults.ANALYSIS_FORMAT

        internal fun build(): CameraConfiguration {
            requireUsable("targetResolution", targetResolution)
            requireUsable("analysisResolution", analysisResolution)
            return CameraConfiguration(this)
        }

        private fun requireUsable(field: String, size: Resolution?) {
            require(size == null || (size.width > 0 && size.height > 0)) {
                "$field must have a positive width and height, was ${size?.width}x${size?.height}"
            }
        }
    }
}

/** A configuration with the fields [block] sets and the defaults for the rest. */
public fun CameraConfiguration(block: CameraConfiguration.Builder.() -> Unit = {}): CameraConfiguration =
    CameraConfiguration.Builder(null).apply(block).build()

/**
 * The 1.x named-argument factory, kept only so a 1.x call gets the rewrite to the DSL. It does not
 * compile. `directory` has no replacement: core writes app-owned files, and the saver plugin
 * publishes to the gallery.
 */
@Deprecated(
    message = "CameraConfiguration is built with its DSL in 2.0: CameraConfiguration { flashMode = FlashMode.AUTO }. " +
        "directory is gone: captures are app-owned files, and the saver plugin publishes to the gallery.",
    replaceWith = ReplaceWith(
        "CameraConfiguration { this.flashMode = flashMode; this.torchMode = torchMode; " +
            "this.cameraLens = cameraLens; this.imageFormat = imageFormat; " +
            "this.qualityPrioritization = qualityPrioritization; this.cameraDeviceType = cameraDeviceType; " +
            "this.aspectRatio = aspectRatio; " +
            "this.targetResolution = targetResolution?.let { Resolution(it.first, it.second) }; " +
            "this.mirrorFrontCamera = mirrorFrontCamera; this.cameraId = cameraId; " +
            "this.outputDirectory = outputDirectory; this.frameAnalysis = frameAnalysis; " +
            "this.analysisResolution = analysisResolution; this.analysisFormat = analysisFormat }",
        "com.kashif.cameraK.state.Resolution",
    ),
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER", "DEPRECATION_ERROR")
public fun CameraConfiguration(
    flashMode: FlashMode = FlashMode.OFF,
    torchMode: TorchMode = TorchMode.OFF,
    cameraLens: CameraLens = CameraLens.BACK,
    imageFormat: ImageFormat = ImageFormat.JPEG,
    qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED,
    cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    targetResolution: Pair<Int, Int>? = null,
    directory: Directory = Directory.DOCUMENTS,
    mirrorFrontCamera: Boolean = false,
    cameraId: String? = null,
    outputDirectory: String? = null,
    frameAnalysis: Boolean = false,
    analysisResolution: Resolution? = null,
    analysisFormat: FrameFormat = FrameFormat.YUV_420,
): CameraConfiguration = throw UnsupportedOperationException("Use CameraConfiguration { … }")

/** The one source of the configuration's defaults (CC-21); the builder reads every default here. */
internal object CameraDefaults {
    /** The platform default camera (design §6.1 "Default lens"). */
    val LENS: CameraLens? = null
    val DEVICE_TYPE = CameraDeviceType.DEFAULT
    val CAMERA_ID: String? = null
    val FLASH = FlashMode.OFF
    val TORCH = TorchMode.OFF
    const val ZOOM_RATIO = 1f

    /** The platform's default exposure. */
    const val EXPOSURE_COMPENSATION_INDEX = 0
    val IMAGE_FORMAT = ImageFormat.JPEG
    val QUALITY = QualityPrioritization.BALANCED

    /** What every rememberCameraKState user already gets. */
    val ASPECT_RATIO = AspectRatio.RATIO_16_9
    val TARGET_RESOLUTION: Resolution? = null
    val TARGET_ORIENTATION: DeviceOrientation? = null

    /** The location 1.x used for Directory.DOCUMENTS, the shipped default. */
    val OUTPUT_DIRECTORY: String? = null
    const val MIRROR_FRONT_CAMERA = false
    const val FRAME_ANALYSIS = false
    val ANALYSIS_RESOLUTION: Resolution? = null
    val ANALYSIS_FORMAT = FrameFormat.YUV_420
}
