package org.company.app.devicematrix

import dev.kashifmehmood.camerak.capabilities.CameraCapabilities
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.enums.QualityPrioritization
import dev.kashifmehmood.camerak.frames.FrameFormat
import dev.kashifmehmood.camerak.video.VideoQuality

/** The matrix's sections, in the order they run; [key] names one in the launch arguments. */
internal enum class MatrixSection(val key: String) {
    FEATURES("features"),
    CONFIG_FLASH("configflash"),
    ANALYSIS("analysis"),
    PLUGINS("plugins"),
    VIDEO("video"),
    LIFECYCLE("lifecycle"),
    PHOTO("photo"),
    ;

    companion object {
        fun ofKey(key: String): MatrixSection? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** A width and height that compare by value, unlike the library's Resolution. */
internal data class MatrixSize(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"
}

/** What one case asks of the camera. [params] are its parameters as the report names them, in order. */
internal sealed interface CaseSpec {
    val section: MatrixSection
    val params: List<Pair<String, String>>
}

internal data class PhotoSpec(
    val lens: CameraLens,
    val deviceType: CameraDeviceType,
    val aspectRatio: AspectRatio,
    val targetResolution: MatrixSize?,
    val imageFormat: ImageFormat,
    val quality: QualityPrioritization,
    val mirrorFrontCamera: Boolean,
    val flashMode: FlashMode,
) : CaseSpec {
    override val section get() = MatrixSection.PHOTO
    override val params get() = listOf(
        "lens" to lens.name,
        "deviceType" to deviceType.name,
        "aspectRatio" to aspectRatio.name,
        "targetResolution" to (targetResolution?.toString() ?: "null"),
        "imageFormat" to imageFormat.name,
        "quality" to quality.name,
        "mirrorFrontCamera" to mirrorFrontCamera.toString(),
        "flashMode" to flashMode.name,
    )

    /** The fields only a new holder applies; cases that share it share one open camera. */
    val holderKey: PhotoSpec get() = copy(flashMode = FlashMode.OFF)
}

/** The configuration's initial flash mode, read back once the camera is open. */
internal data class ConfigFlashSpec(val lens: CameraLens, val flashMode: FlashMode) : CaseSpec {
    override val section get() = MatrixSection.CONFIG_FLASH
    override val params get() = listOf("lens" to lens.name, "flashMode" to flashMode.name)
}

internal enum class FeatureCheck {
    ZOOM_MIN,
    ZOOM_MID,
    ZOOM_MAX,
    TORCH_ON,
    TORCH_OFF,
    FOCUS_CENTER,
    FOCUS_TOP_LEFT,
    FOCUS_TOP_RIGHT,
    FOCUS_BOTTOM_LEFT,
    FOCUS_BOTTOM_RIGHT,
    EXPOSURE_MIN,
    EXPOSURE_ZERO,
    EXPOSURE_MAX,
    EXPOSURE_RESET,
}

/** One control on one camera of the capabilities, opened by its id. */
internal data class FeatureSpec(
    val cameraId: String,
    val lens: CameraLens,
    val deviceType: CameraDeviceType,
    val check: FeatureCheck,
) : CaseSpec {
    override val section get() = MatrixSection.FEATURES
    override val params get() = listOf(
        "cameraId" to cameraId,
        "lens" to lens.name,
        "deviceType" to deviceType.name,
        "check" to check.name,
    )
}

internal data class AnalysisSpec(val lens: CameraLens, val format: FrameFormat, val resolution: MatrixSize?) :
    CaseSpec {
    override val section get() = MatrixSection.ANALYSIS
    override val params get() = listOf(
        "lens" to lens.name,
        "analysisFormat" to format.name,
        "analysisResolution" to (resolution?.toString() ?: "null"),
    )
}

internal enum class PluginKind {
    ANALYZER,
    OCR,
    QR,
    MEDIA_SAVER_PHOTO,
    MEDIA_SAVER_VIDEO,
}

internal data class PluginSpec(val plugin: PluginKind) : CaseSpec {
    override val section get() = MatrixSection.PLUGINS
    override val params get() = listOf("plugin" to plugin.name)
}

internal data class VideoSpec(
    val lens: CameraLens,
    val aspectRatio: AspectRatio,
    val quality: VideoQuality,
    val audio: Boolean,
) : CaseSpec {
    override val section get() = MatrixSection.VIDEO
    override val params get() = listOf(
        "lens" to lens.name,
        "aspectRatio" to aspectRatio.name,
        "quality" to quality.name,
        "audio" to audio.toString(),
    )
}

internal enum class LifecycleKind {
    START_STOP_20,
    CLOSED_COMMANDS,
    SWITCH_WHILE_RECORDING,
}

internal data class LifecycleSpec(val kind: LifecycleKind) : CaseSpec {
    override val section get() = MatrixSection.LIFECYCLE
    override val params get() = listOf("kind" to kind.name)
}

/** A case and its place in the whole matrix; [index] is what a resume offset counts. */
internal data class MatrixCase(val index: Int, val spec: CaseSpec) {
    val id: String get() = spec.section.key + "/" + spec.params.joinToString("/") { it.second }
}

/** The target resolutions the photo matrix asks for, null being the platform's choice. */
internal val photoResolutions: List<MatrixSize?> =
    listOf(null, MatrixSize(1280, 720), MatrixSize(1920, 1080), MatrixSize(3840, 2160))

/** The analysis resolutions, null following the preview. */
internal val analysisResolutions: List<MatrixSize?> = listOf(null, MatrixSize(640, 480))

/**
 * Every case of the matrix for the device [capabilities] describe, in run order, each with its index
 * in the whole matrix. The lenses and cameras come from [capabilities]; every other dimension is
 * every value of its enum.
 */
internal fun enumerateCases(capabilities: CameraCapabilities): List<MatrixCase> {
    val lenses = CameraLens.entries.filter { capabilities.lenses(it).isNotEmpty() }
    val cameras = capabilities.allLenses.filterNot { it.isExternal }
    val specs = buildList {
        for (camera in cameras) {
            FeatureCheck.entries.forEach { add(FeatureSpec(camera.id, camera.lens, camera.deviceType, it)) }
        }
        for (lens in lenses) FlashMode.entries.forEach { add(ConfigFlashSpec(lens, it)) }
        for (lens in lenses) {
            for (format in FrameFormat.entries) analysisResolutions.forEach { add(AnalysisSpec(lens, format, it)) }
        }
        PluginKind.entries.forEach { add(PluginSpec(it)) }
        for (lens in lenses) {
            for (ratio in AspectRatio.entries) {
                for (quality in VideoQuality.entries) {
                    listOf(false, true).forEach {
                        add(VideoSpec(lens, ratio, quality, it))
                    }
                }
            }
        }
        LifecycleKind.entries.forEach { add(LifecycleSpec(it)) }
        for (lens in lenses) addPhotoCases(lens)
    }
    return specs.mapIndexed { index, spec -> MatrixCase(index, spec) }
}

private fun MutableList<CaseSpec>.addPhotoCases(lens: CameraLens) {
    val mirrors = if (lens == CameraLens.FRONT) listOf(false, true) else listOf(false)
    for (type in CameraDeviceType.entries) {
        for (ratio in AspectRatio.entries) {
            for (resolution in photoResolutions) {
                for (format in ImageFormat.entries) {
                    for (quality in QualityPrioritization.entries) {
                        for (mirror in mirrors) {
                            FlashMode.entries.forEach {
                                add(PhotoSpec(lens, type, ratio, resolution, format, quality, mirror, it))
                            }
                        }
                    }
                }
            }
        }
    }
}
