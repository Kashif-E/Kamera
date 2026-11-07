package org.company.app.devicematrix

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError

/** A case's verdict; [label] is how the report writes it. */
internal enum class Verdict(val label: String) {
    /** It succeeded and every check held. */
    PASS("PASS"),

    /** A typed Unsupported or NoCamera that the capabilities predicted. */
    UNSUPPORTED_OK("UNSUPPORTED-OK"),

    /** Anything else: an unpredicted error, a failed check, a timeout or an exception. */
    FAIL("FAIL"),

    /** Refused for a permission the device has not granted: not tested, not a library result. */
    BLOCKED("BLOCKED"),
}

/**
 * What the capabilities say a case will be refused for: [unsupported] features, or [noCamera]. With
 * [mayStillSucceed] a success is as acceptable as the refusal, as for a device type a lens lacks,
 * where Android documents a fallback to the lens's default camera.
 */
internal data class Prediction(
    val unsupported: Set<CameraFeature> = emptySet(),
    val noCamera: Boolean = false,
    val mayStillSucceed: Boolean = false,
) {
    val refuses: Boolean get() = noCamera || unsupported.isNotEmpty()

    operator fun plus(other: Prediction): Prediction = Prediction(
        unsupported + other.unsupported,
        noCamera || other.noCamera,
        mayStillSucceed || other.mayStillSucceed,
    )

    companion object {
        val NONE = Prediction()
    }
}

/** How a case ended, before its checks are judged. */
internal sealed interface Outcome {
    /** Every step returned success. */
    data object Completed : Outcome

    /** [step] returned [error]. */
    class Refused(val step: String, val error: CameraKError) : Outcome

    /** [step] did not finish within its bound. */
    class TimedOut(val step: String) : Outcome

    /** The runner caught [message] from the library. */
    class Threw(val message: String) : Outcome
}

internal class Judgement(val verdict: Verdict, val reason: String?)

/** The verdict for [outcome], given what the capabilities [predicted] and the checks that failed. */
internal fun judge(outcome: Outcome, predicted: Prediction, failedChecks: List<String>): Judgement {
    val checks = failedChecks.joinToString("; ")
    fun failing(reason: String) = Judgement(Verdict.FAIL, listOf(reason, checks).filter { it.isNotEmpty() }.joinToString("; "))
    return when (outcome) {
        Outcome.Completed -> when {
            predicted.refuses && !predicted.mayStillSucceed ->
                failing("succeeded although the capabilities predicted ${predicted.describe()}")
            failedChecks.isEmpty() -> Judgement(Verdict.PASS, null)
            else -> Judgement(Verdict.FAIL, checks)
        }
        is Outcome.Refused -> {
            val error = outcome.error
            val what = "${outcome.step}: ${describe(error)}"
            val foreseen = (error is CameraKError.Unsupported && error.feature in predicted.unsupported) ||
                (error is CameraKError.NoCamera && predicted.noCamera)
            when {
                error is CameraKError.Permission -> Judgement(Verdict.BLOCKED, what)
                failedChecks.isNotEmpty() -> failing(what)
                foreseen -> Judgement(Verdict.UNSUPPORTED_OK, "$what, predicted")
                error is CameraKError.Unsupported || error is CameraKError.NoCamera ->
                    failing("$what, not predicted by the capabilities")
                else -> failing(what)
            }
        }
        is Outcome.TimedOut -> failing("timeout at ${outcome.step}")
        is Outcome.Threw -> failing("exception: ${outcome.message}")
    }
}

private fun Prediction.describe(): String =
    (unsupported.map { "Unsupported($it)" } + listOfNotNull("NoCamera".takeIf { noCamera })).joinToString(" or ")

/** A typed error as the report writes it, such as `Unsupported(IMAGE_FORMAT)` or `NoCamera(FRONT)`. */
internal fun describe(error: CameraKError): String {
    val fields = when (error) {
        is CameraKError.Permission -> "(${error.permission},${error.status})"
        is CameraKError.NoCamera -> "(${error.lens})"
        is CameraKError.Storage -> "(${error.kind})"
        is CameraKError.Unsupported -> "(${error.feature})"
        is CameraKError.NativeLibraryMissing -> "(${error.platform})"
        is CameraKError.Hardware -> "(${error.platformCode})"
        is CameraKError.Timeout -> "(${error.operation})"
        is CameraKError.Busy -> "(${error.operation})"
        is CameraKError.InvalidState -> "(${error.operation})"
        is CameraKError.InvalidArgument -> "(${error.operation})"
        is CameraKError.Internal -> "(${error.cause?.let { "${it::class.simpleName}: ${it.message}" }})"
        else -> ""
    }
    val cause = error.cause?.takeIf { error !is CameraKError.Internal }?.let { " [cause ${it::class.simpleName}: ${it.message}]" }
    return (error::class.simpleName ?: "CameraKError") + fields + cause.orEmpty()
}

/** What a photo start is predicted to be refused for: the lens, the device type, the format and the ratio. */
internal fun photoStartPrediction(capabilities: CameraCapabilities, spec: PhotoSpec): Prediction {
    val lens = lensPrediction(capabilities, spec.lens)
    val type = if (!lens.noCamera && spec.deviceType != CameraDeviceType.DEFAULT &&
        spec.deviceType !in capabilities.availableDeviceTypes(spec.lens)
    ) {
        Prediction(noCamera = true, mayStillSucceed = true)
    } else {
        Prediction.NONE
    }
    return lens + type + formatPrediction(capabilities, spec.imageFormat) + ratioPrediction(capabilities, spec.aspectRatio)
}

private fun formatPrediction(capabilities: CameraCapabilities, format: ImageFormat): Prediction =
    if (format in capabilities.supportedImageFormats) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.IMAGE_FORMAT))
    }

private fun ratioPrediction(capabilities: CameraCapabilities, ratio: AspectRatio): Prediction {
    val square = ratio == AspectRatio.RATIO_1_1 && !capabilities.supportsSquareCapture
    return if (ratio in capabilities.supportedAspectRatios && !square) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.ASPECT_RATIO))
    }
}

/** Whether the lens has no camera at all. */
internal fun lensPrediction(capabilities: CameraCapabilities, lens: CameraLens): Prediction =
    if (capabilities.lenses(lens).isEmpty()) Prediction(noCamera = true) else Prediction.NONE

internal fun flashPrediction(capabilities: CameraCapabilities, cameraId: String, mode: FlashMode): Prediction = when {
    mode in capabilities.supportedFlashModes(cameraId) -> Prediction.NONE
    mode == FlashMode.AUTO -> Prediction(unsupported = setOf(CameraFeature.FLASH_AUTO))
    else -> Prediction(unsupported = setOf(CameraFeature.FLASH_ON))
}

internal fun torchPrediction(capabilities: CameraCapabilities, cameraId: String, mode: TorchMode): Prediction =
    if (mode in capabilities.supportedTorchModes(cameraId)) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.TORCH))
    }

internal fun focusPrediction(capabilities: CameraCapabilities, cameraId: String): Prediction =
    if (capabilities.supportsFocusPoint(cameraId)) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.FOCUS_POINT))
    }

internal fun exposurePrediction(capabilities: CameraCapabilities, cameraId: String): Prediction =
    if (capabilities.exposureCompensationRange(cameraId) != null) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.EXPOSURE_COMPENSATION))
    }

/** A reset is refused only by a camera with neither a focus point nor exposure compensation. */
internal fun resetPrediction(capabilities: CameraCapabilities, cameraId: String): Prediction =
    if (capabilities.supportsFocusPoint(cameraId) || capabilities.exposureCompensationRange(cameraId) != null) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.FOCUS_POINT))
    }

internal fun videoPrediction(capabilities: CameraCapabilities, spec: VideoSpec): Prediction {
    val quality = if (spec.quality in capabilities.supportedVideoQualities) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.VIDEO_QUALITY))
    }
    return lensPrediction(capabilities, spec.lens) + ratioPrediction(capabilities, spec.aspectRatio) + quality
}

internal fun analysisPrediction(capabilities: CameraCapabilities, spec: AnalysisSpec): Prediction {
    val format = if (spec.format in capabilities.supportedAnalysisFormats) {
        Prediction.NONE
    } else {
        Prediction(unsupported = setOf(CameraFeature.ANALYSIS_FORMAT))
    }
    return lensPrediction(capabilities, spec.lens) + format
}
