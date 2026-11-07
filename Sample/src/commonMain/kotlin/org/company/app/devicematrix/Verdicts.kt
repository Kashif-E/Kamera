package org.company.app.devicematrix

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameFormat

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
internal fun judge(outcome: Outcome, predicted: Prediction, failedChecks: List<String>): Judgement = TODO()

/** A typed error as the report writes it, such as `Unsupported(IMAGE_FORMAT)` or `NoCamera(FRONT)`. */
internal fun describe(error: CameraKError): String = TODO()

/** What a photo start is predicted to be refused for: the lens, the device type, the format and the ratio. */
internal fun photoStartPrediction(capabilities: CameraCapabilities, spec: PhotoSpec): Prediction = TODO()

/** Whether the lens has no camera at all. */
internal fun lensPrediction(capabilities: CameraCapabilities, lens: CameraLens): Prediction = TODO()

internal fun flashPrediction(capabilities: CameraCapabilities, cameraId: String, mode: FlashMode): Prediction = TODO()

internal fun torchPrediction(capabilities: CameraCapabilities, cameraId: String, mode: TorchMode): Prediction =
    TODO()

internal fun focusPrediction(capabilities: CameraCapabilities, cameraId: String): Prediction = TODO()

internal fun exposurePrediction(capabilities: CameraCapabilities, cameraId: String): Prediction = TODO()

internal fun videoPrediction(capabilities: CameraCapabilities, spec: VideoSpec): Prediction = TODO()

internal fun analysisPrediction(capabilities: CameraCapabilities, spec: AnalysisSpec): Prediction = TODO()
