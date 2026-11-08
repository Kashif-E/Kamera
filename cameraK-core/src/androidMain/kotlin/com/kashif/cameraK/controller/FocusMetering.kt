package com.kashif.cameraK.controller

import android.graphics.Rect
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.google.common.util.concurrent.ListenableFuture
import com.kashif.cameraK.capabilities.PhysicalMetering
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume

private const val TAG = "CameraK"

// Whether a lens meters [flag] at a point; nothing pinned restricts nothing.
private fun PhysicalMetering?.meters(flag: Int): Boolean = when {
    this == null -> true
    flag == FocusMeteringAction.FLAG_AF -> autoFocus
    flag == FocusMeteringAction.FLAG_AE -> autoExposure
    else -> autoWhiteBalance
}

/** How CameraX draws the preview (`Preview.getResolutionInfo()`): its buffer size, crop and rotation to the display. */
internal class PreviewGeometry(val resolution: Size, val cropRect: Rect, val rotationDegrees: Int)

/** A pixel of the camera's output buffer, in its own orientation. */
internal data class SurfacePoint(val x: Float, val y: Float)

/**
 * The buffer pixel under [point], a point of the preview's content rectangle normalized to [0, 1]
 * over the visible crop, as the display shows it (w13 G1). The preview draws [cropRect] of a
 * [resolution] buffer turned clockwise by [rotationDegrees] and, when [mirrored] (the front camera),
 * flipped left to right; the mapping undoes the mirror, then the rotation, then scales into the crop.
 */
internal fun contentPointToSurfacePoint(
    point: NormalizedPoint,
    cropRect: Rect,
    resolution: Size,
    rotationDegrees: Int,
    mirrored: Boolean,
): SurfacePoint {
    val x = if (mirrored) 1f - point.x else point.x
    val y = point.y
    val (bufferX, bufferY) = when (val degrees = (rotationDegrees % 360 + 360) % 360) {
        0 -> x to y
        90 -> y to 1f - x
        180 -> 1f - x to 1f - y
        270 -> 1f - y to x
        else -> throw IllegalArgumentException("rotation $degrees is not a multiple of 90")
    }
    return SurfacePoint(
        (cropRect.left + bufferX * cropRect.width()).coerceIn(0f, resolution.width.toFloat()),
        (cropRect.top + bufferY * cropRect.height()).coerceIn(0f, resolution.height.toFloat()),
    )
}

/**
 * What a bound camera meters with, as CameraX describes it: [focusPoint] where it meters a centre
 * AF|AE action, and [exposureRange] and [exposureStep] from its `ExposureState` where it supports
 * compensation, both null otherwise.
 */
internal data class MeteringFeatures(val focusPoint: Boolean, val exposureRange: IntRange?, val exposureStep: Float?)

/**
 * [camera]'s [MeteringFeatures], with only what [pinned] meters at a point when a bind pins a physical
 * lens: CameraX describes the logical camera the pin binds. CameraX's calls can throw; the caller
 * decides what that means.
 */
internal fun meteringFeaturesOf(camera: Camera, pinned: PhysicalMetering? = null): MeteringFeatures {
    val info = camera.cameraInfo
    val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
    val flags = listOf(FocusMeteringAction.FLAG_AF, FocusMeteringAction.FLAG_AE).filter { pinned.meters(it) }
    val focusPoint = flags.isNotEmpty() &&
        info.isFocusMeteringSupported(FocusMeteringAction.Builder(centre, flags.reduce(Int::or)).build())
    val exposure = info.exposureState.takeIf { it.isExposureCompensationSupported }
    return MeteringFeatures(
        focusPoint = focusPoint,
        exposureRange = exposure?.exposureCompensationRange?.let { it.lower..it.upper },
        exposureStep = exposure?.exposureCompensationStep?.toFloat(),
    )
}

/**
 * Focus, metering and exposure compensation on the bound camera (w13 Z-focus-android). [focusAt]
 * maps the tap through the preview's geometry and meters there with AF, AE and AWB where the camera
 * supports each, under CameraX's default auto-cancel. Exposure has no CameraX observer: the index
 * `ExposureState` reads back once CameraX completes a request is what [publishExposure] reports
 * (rule 5). The last successful index is the desired one, applied to every camera whose control
 * becomes active, clamped into its range, and dropped to 0 on a camera without one (rule 6); a focus
 * point is never re-applied.
 *
 * Each request belongs to the run of the bound camera it was issued on, which the next bind or a stop
 * ends. A newer request of the same kind or a reset in its run, or the rebind that ended its run,
 * supersedes it: CameraX cancels it, and the caller gets Busy. The stop that ended its run, or CameraX
 * cancelling it with its run still live (the camera closed), is Closed. An exposure request or a reset
 * CameraX completes once its run has ended was applied to a camera no longer bound: it is Busy after a
 * rebind and Closed after a stop, and the desired index stays as it was. Main thread; every future is
 * awaited on [executor].
 */
internal class FocusMetering(
    private val backend: CameraXBackend,
    private val camera: () -> Camera?,
    private val pinnedMetering: () -> PhysicalMetering?,
    private val preview: () -> Preview?,
    private val mirrored: () -> Boolean,
    private val executor: Executor,
    private val isReleased: () -> Boolean,
    private val publishExposure: (Int) -> Unit,
) {
    private var desiredExposure = 0

    private var run = Run()

    /** The exposure index the next camera whose control becomes active is given. */
    fun store(exposureIndex: Int) {
        desiredExposure = exposureIndex
    }

    /** A bind replaced the use cases: what was in flight on the old ones is superseded. */
    fun onBound() = endRun(RunEnd.REBIND)

    /** The session stopped: what is in flight was cut short by the stop. */
    fun onStopped() = endRun(RunEnd.STOP)

    private fun endRun(end: RunEnd) {
        run.end = end
        run = Run()
    }

    /** The exposure index [camera] reports; 0 for one without compensation or whose state cannot be read. */
    fun exposureIndexOf(camera: Camera): Int = try {
        camera.cameraInfo.exposureState.exposureCompensationIndex
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "Reading the exposure compensation index failed", e)
        0
    }

    /**
     * Gives [camera], whose control has just become active, the desired index clamped into its range;
     * on a camera without compensation the index is dropped. The read-back is published once CameraX
     * completes the request, unless another camera is bound by then.
     */
    fun applyDesiredTo(camera: Camera) {
        val range = features(camera)?.exposureRange
        if (range == null) {
            desiredExposure = 0
            return
        }
        val target = desiredExposure.coerceIn(range)
        if (target == exposureIndexOf(camera)) {
            publishExposure(target)
            return
        }
        val future = issue { camera.cameraControl.setExposureCompensationIndex(target) }.getOrElse {
            CameraKLogger.e(TAG, "Re-applying the exposure compensation failed", it)
            return
        }
        future.addListener({
            if (isReleased() || camera !== this.camera()) return@addListener
            future.outcome().onFailure { logFailure("Re-applying the exposure compensation", it) }
            publishExposure(exposureIndexOf(camera))
        }, executor)
    }

    suspend fun focusAt(point: NormalizedPoint): CameraKResult<Unit> {
        val camera = camera() ?: return NOT_READY
        if (features(camera)?.focusPoint != true) return unsupported(CameraFeature.FOCUS_POINT)
        val geometry = preview()?.let(backend::previewGeometry)
            ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.FOCUS_POINT))
        val surface = contentPointToSurfacePoint(
            point,
            geometry.cropRect,
            geometry.resolution,
            geometry.rotationDegrees,
            mirrored(),
        )
        val meteringPoint =
            backend.meteringPoint(surface.x, surface.y, geometry.resolution.width, geometry.resolution.height)
        // CameraX drops a flag the camera cannot meter with; dropping it here tells whether AF is in the action.
        // CameraX answers for the logical camera a pin binds, so a flag the pinned lens cannot meter is
        // dropped too: AF on a fixed-focus lens would never lock.
        val pinned = pinnedMetering()
        val flags = try {
            METERING_FLAGS.filter {
                pinned.meters(it) &&
                    camera.cameraInfo.isFocusMeteringSupported(FocusMeteringAction.Builder(meteringPoint, it).build())
            }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading which metering the camera supports at the point failed", e)
            return CameraKResult.Failure(CameraKError.Hardware(null, e))
        }
        if (flags.isEmpty()) return unsupported(CameraFeature.FOCUS_POINT)
        val action = FocusMeteringAction.Builder(meteringPoint, flags.reduce(Int::or)).build()

        val request = Request(run, ++run.focusRequest)
        val outcome = issue { camera.cameraControl.startFocusAndMetering(action) }.await()
        if (isReleased()) return AndroidCameraEngine.CLOSED
        return outcome.fold(
            onSuccess = { result ->
                when {
                    result.isFocusSuccessful -> SUCCESS
                    // A fixed-focus camera only meters: CameraX completing the action is the outcome.
                    FocusMeteringAction.FLAG_AF !in flags -> SUCCESS
                    else -> CameraKResult.Failure(CameraKError.Timeout(CameraOperation.FOCUS_POINT))
                }
            },
            onFailure = {
                val busy = request.isSuperseded(Run::focusRequest)
                failure(it, busy, CameraOperation.FOCUS_POINT, "Focusing at a point")
            },
        )
    }

    suspend fun setExposureCompensation(index: Int): CameraKResult<Unit> {
        val camera = camera() ?: return NOT_READY
        if (features(camera)?.exposureRange == null) return unsupported(CameraFeature.EXPOSURE_COMPENSATION)
        val request = Request(run, ++run.exposureRequest)
        val outcome = issue { camera.cameraControl.setExposureCompensationIndex(index) }.await()
        if (isReleased()) return AndroidCameraEngine.CLOSED
        return outcome.fold(
            onSuccess = {
                request.run.end?.let {
                    return appliedAfter(it, CameraOperation.EXPOSURE_COMPENSATION, "Setting the exposure compensation")
                }
                desiredExposure = index
                if (camera === this.camera()) publishExposure(exposureIndexOf(camera))
                SUCCESS
            },
            onFailure = {
                val busy = request.isSuperseded(Run::exposureRequest)
                failure(it, busy, CameraOperation.EXPOSURE_COMPENSATION, "Setting the exposure compensation")
            },
        )
    }

    /**
     * Cancels focus and metering and, where the camera has compensation, sets index 0; awaits both.
     * `Unsupported(FOCUS_POINT)` on a camera with neither.
     */
    suspend fun resetFocusAndExposure(): CameraKResult<Unit> {
        val camera = camera() ?: return NOT_READY
        val features = features(camera)
        val compensates = features?.exposureRange != null
        if (features?.focusPoint != true && !compensates) return unsupported(CameraFeature.FOCUS_POINT)
        val focus = Request(run, ++run.focusRequest)
        val exposure = Request(run, ++run.exposureRequest)
        val cancel = issue { camera.cameraControl.cancelFocusAndMetering() }
        val zero = if (compensates) issue { camera.cameraControl.setExposureCompensationIndex(0) } else null
        val cancelled = cancel.await()
        val zeroed = zero?.await()
        if (isReleased()) return AndroidCameraEngine.CLOSED
        cancelled.onFailure {
            val busy = focus.isSuperseded(Run::focusRequest)
            return failure(it, busy, CameraOperation.FOCUS_POINT, "Cancelling focus and metering")
        }
        zeroed?.onFailure {
            val busy = exposure.isSuperseded(Run::exposureRequest)
            return failure(it, busy, CameraOperation.FOCUS_POINT, "Resetting the exposure compensation")
        }
        if (zeroed != null) {
            exposure.run.end?.let {
                return appliedAfter(it, CameraOperation.FOCUS_POINT, "Resetting the exposure compensation")
            }
        }
        desiredExposure = 0
        if (zeroed != null && camera === this.camera()) publishExposure(exposureIndexOf(camera))
        return SUCCESS
    }

    private fun features(camera: Camera): MeteringFeatures? = try {
        meteringFeaturesOf(camera, pinnedMetering())
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "Reading the camera's focus and exposure support failed", e)
        null
    }

    // CameraX's own cancellation is Busy when something in the session superseded it, else the camera
    // closed (rule 10: not an error). Anything else is the hardware's.
    private fun failure(
        cause: Throwable,
        busy: Boolean,
        operation: CameraOperation,
        name: String,
    ): CameraKResult<Unit> {
        if (cause is CameraControl.OperationCanceledException || cause is CancellationException) {
            CameraKLogger.d(TAG, "$name was cancelled by CameraX (${cause::class.java.simpleName}: ${cause.message})")
            return if (busy) CameraKResult.Failure(CameraKError.Busy(operation)) else AndroidCameraEngine.CLOSED
        }
        CameraKLogger.e(TAG, "$name failed", cause)
        return CameraKResult.Failure(CameraKError.Hardware(null, cause))
    }

    // A request CameraX applied once its run had ended reached a camera no longer bound; the caller retries
    // on the bound one, or on the next start.
    private fun appliedAfter(end: RunEnd, operation: CameraOperation, name: String): CameraKResult<Unit> {
        CameraKLogger.d(TAG, "$name completed on a camera no longer bound (${end.name.lowercase()})")
        if (end == RunEnd.STOP) return AndroidCameraEngine.CLOSED
        return CameraKResult.Failure(CameraKError.Busy(operation))
    }

    private fun logFailure(name: String, cause: Throwable) {
        if (cause is CameraControl.OperationCanceledException || cause is CancellationException) {
            CameraKLogger.d(TAG, "$name was cancelled by CameraX (${cause::class.java.simpleName}: ${cause.message})")
        } else {
            CameraKLogger.w(TAG, "$name failed", cause)
        }
    }

    // A CameraX call that throws instead of failing its future fails the same way.
    private inline fun <T> issue(call: () -> ListenableFuture<T>): Result<ListenableFuture<T>> = try {
        Result.success(call())
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun <T> Result<ListenableFuture<T>>.await(): Result<T> {
        val future = getOrElse { return Result.failure(it) }
        return suspendCancellableCoroutine { continuation ->
            future.addListener({ continuation.resume(future.outcome()) }, executor)
        }
    }

    // The outcome of a future that is done; its failure is the cause CameraX failed it with.
    private fun <T> ListenableFuture<T>.outcome(): Result<T> = try {
        Result.success(get())
    } catch (e: ExecutionException) {
        Result.failure(e.cause ?: e)
    } catch (e: CancellationException) {
        Result.failure(e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Result.failure(e)
    }

    private fun unsupported(feature: CameraFeature): CameraKResult<Unit> =
        CameraKResult.Failure(CameraKError.Unsupported(feature))

    private enum class RunEnd { REBIND, STOP }

    // One bound camera's run: its requests' numbers, and what ended it (null while it is live).
    private class Run {
        var focusRequest = 0
        var exposureRequest = 0
        var end: RunEnd? = null
    }

    private class Request(val run: Run, val number: Int) {
        // Busy: a newer request of its kind or a reset in its run, or the rebind that ended the run.
        fun isSuperseded(latest: (Run) -> Int): Boolean = number != latest(run) || run.end == RunEnd.REBIND
    }

    private companion object {
        val METERING_FLAGS = listOf(
            FocusMeteringAction.FLAG_AF,
            FocusMeteringAction.FLAG_AE,
            FocusMeteringAction.FLAG_AWB,
        )
        val SUCCESS = CameraKResult.Success(Unit)
        val NOT_READY = CameraKResult.Failure(CameraKError.NotReady)
    }
}
