package com.kashif.cameraK.controller

import android.graphics.Rect
import android.hardware.camera2.CaptureResult
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
 * supports each, under CameraX's default auto-cancel. When CameraX answers that AF did not lock, the
 * autofocus state of the next capture result tells AF that ended, Success as on iOS, from AF that had not
 * ended when CameraX gave up, Timeout. Exposure has no CameraX observer: the index
 * `ExposureState` reads back once CameraX completes a request, or once a capture result carries the
 * requested index, is what [publishExposure] reports (rule 5). The last successful index is the desired one, applied to every camera whose control
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

    // The capture results of the run's session: the run, the session reporting and the one whose results no
    // longer count (the one reporting when the previous run ended: a stop leaves frames in the pipeline, and a
    // restart on the same camera binds nothing), how many arrived, the index the latest carried, null until the
    // first, which shows the session configured and streaming, whether results count yet (after a stop, only
    // once the camera has opened again, since a session stopped before its first result was never named), and
    // the autofocus state the latest carried.
    private val results = MutableStateFlow(Results(run, null, null, 0, null, counting = true))

    // A camera whose desired index waits for its session's first capture result.
    private var pendingApply: Camera? = null

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
        results.update { Results(run, null, it.session ?: it.ignored, 0, null, counting = end == RunEnd.REBIND) }
        pendingApply = null
    }

    /** A capture result of [session] carries exposure compensation [index] and autofocus state [afState]; any thread. */
    fun onCaptureReported(session: Any, index: Int, afState: Int?) {
        val before = results.getAndUpdate {
            if (!it.counts(
                    session,
                )
            ) {
                it
            } else {
                Results(it.run, session, it.ignored, it.count + 1, index, counting = true, afState)
            }
        }
        if (before.counts(session) && before.index == null) executor.execute { onStreaming(before.run) }
    }

    // Main thread: the session of [streaming]'s run produced its first capture result.
    private fun onStreaming(streaming: Run) {
        val camera = pendingApply ?: return
        if (streaming !== run) return
        pendingApply = null
        if (!isReleased() && camera === this.camera()) applyNow(camera)
    }

    /**
     * The camera closed within the run, interrupted. CameraX configures a new session when it reopens and drops an
     * exposure request sent before that session streams, so the run streams again only at a result of a session
     * other than the one reporting now, whether that arrives before or after the open report. The count carries on,
     * for requests in flight.
     */
    fun onInterrupted() {
        val current = run
        results.update {
            if (it.run !== current || it.index == null) {
                it
            } else {
                Results(it.run, null, it.session ?: it.ignored, it.count, null, it.counting)
            }
        }
    }

    /** The camera opened: the closed device can deliver no more results, so the run's results count from here. */
    fun onOpened() {
        val current = run
        results.update {
            if (it.run === current &&
                !it.counting
            ) {
                Results(it.run, null, it.ignored, 0, null, true)
            } else {
                it
            }
        }
    }

    /** The exposure index [camera] reports; 0 for one without compensation or whose state cannot be read. */
    fun exposureIndexOf(camera: Camera): Int = try {
        camera.cameraInfo.exposureState.exposureCompensationIndex
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "Reading the exposure compensation index failed", e)
        0
    }

    /**
     * Gives [camera], whose control has just become active, the desired index clamped into its range,
     * once its session streams; on a camera without compensation the index is dropped. The read-back
     * is published once CameraX completes the request, unless another camera is bound by then.
     */
    fun applyDesiredTo(camera: Camera) {
        val range = features(camera)?.exposureRange
        if (range == null) {
            desiredExposure = 0
            return
        }
        if (desiredExposure.coerceIn(range) == exposureIndexOf(camera)) {
            publishExposure(exposureIndexOf(camera))
            return
        }
        if (results.value.index == null) {
            pendingApply = camera
            return
        }
        applyNow(camera)
    }

    private fun applyNow(camera: Camera) {
        val target = desiredExposure.coerceIn(features(camera)?.exposureRange ?: return)
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
                    else -> unlockedFocus(request)
                }
            },
            onFailure = {
                val busy = request.isSuperseded(Run::focusRequest)
                failure(it, busy, CameraOperation.FOCUS_POINT, "Focusing at a point")
            },
        )
    }

    // CameraX completes an AF action unsuccessful both when AF ends NOT_FOCUSED_LOCKED and when its 5 s deadline
    // passes while AF still scans. Focus that ended is Success, as on iOS, where focus that stops adjusting
    // succeeds; focus still searching is Timeout. The state is read from a capture result reported after CameraX
    // completed, so it is no older than the one that completed the action. A stop before one arrives is Closed;
    // a rebind, a newer tap or a reset since, whose AF the result would describe, is Busy. A streaming camera
    // reports several results a second; none within [UNLOCKED_FOCUS_RESULT_MS] means it is not streaming
    // (CameraX gives up then too) or its results carry no exposure index, and the answer stays Timeout.
    private suspend fun unlockedFocus(request: Request): CameraKResult<Unit> {
        val completed = results.value.count
        val after = withTimeoutOrNull(UNLOCKED_FOCUS_RESULT_MS) {
            results.first { it.run !== request.run || it.count > completed }
        } ?: return CameraKResult.Failure(CameraKError.Timeout(CameraOperation.FOCUS_POINT))
        if (isReleased() || request.run.end == RunEnd.STOP) return AndroidCameraEngine.CLOSED
        if (after.run !== request.run || request.isSuperseded(Run::focusRequest)) {
            return CameraKResult.Failure(CameraKError.Busy(CameraOperation.FOCUS_POINT))
        }
        return when (after.afState) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            -> SUCCESS
            else -> CameraKResult.Failure(CameraKError.Timeout(CameraOperation.FOCUS_POINT))
        }
    }

    suspend fun setExposureCompensation(index: Int): CameraKResult<Unit> {
        val camera = camera() ?: return NOT_READY
        if (features(camera)?.exposureRange == null) return unsupported(CameraFeature.EXPOSURE_COMPENSATION)
        val request = Request(run, ++run.exposureRequest)
        awaitStreaming(request, CameraOperation.EXPOSURE_COMPENSATION)?.let { return it }
        if (request.isSuperseded(Run::exposureRequest)) {
            return CameraKResult.Failure(CameraKError.Busy(CameraOperation.EXPOSURE_COMPENSATION))
        }
        // The command replaces a re-apply still waiting for the session, which would otherwise replace it.
        val replaced = pendingApply
        pendingApply = null
        val issued = results.value.count
        val outcome =
            awaitExposure(request, issued, issue { camera.cameraControl.setExposureCompensationIndex(index) }, index)
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
                if (!busy) applyReplaced(request, replaced)
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
        if (compensates) awaitStreaming(exposure, CameraOperation.FOCUS_POINT)?.let { return it }
        if (focus.isSuperseded(Run::focusRequest) || exposure.isSuperseded(Run::exposureRequest)) {
            return CameraKResult.Failure(CameraKError.Busy(CameraOperation.FOCUS_POINT))
        }
        val replaced = pendingApply.takeIf { compensates }
        if (compensates) pendingApply = null
        val issued = results.value.count
        val cancel = issue { camera.cameraControl.cancelFocusAndMetering() }
        val zero = if (compensates) issue { camera.cameraControl.setExposureCompensationIndex(0) } else null
        val cancelled = cancel.await()
        val zeroed = zero?.let { awaitExposure(exposure, issued, it, 0) }
        if (isReleased()) return AndroidCameraEngine.CLOSED
        cancelled.onFailure {
            val busy = focus.isSuperseded(Run::focusRequest)
            return failure(it, busy, CameraOperation.FOCUS_POINT, "Cancelling focus and metering")
        }
        zeroed?.onFailure {
            val busy = exposure.isSuperseded(Run::exposureRequest)
            if (!busy) applyReplaced(exposure, replaced)
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

    // CameraX completes an exposure request only once a capture result carries the index with auto-exposure
    // CONVERGED, LOCKED or FLASH_REQUIRED, and sets no limit on how long the search may take (a dark scene
    // took 44 frames); every result carries the index from the first the request reaches. A result carrying
    // [index] after the [issued]th of its session completes the request as CameraX's outcome would, whichever
    // comes first.
    private suspend fun awaitExposure(
        request: Request,
        issued: Long,
        call: Result<ListenableFuture<Int>>,
        index: Int,
    ): Result<Int> = coroutineScope {
        val reported = async {
            results.first { it.run === request.run && it.count > issued && it.index == index }
            Result.success(index)
        }
        val completed = async { call.await() }
        select {
            reported.onAwait { it }
            completed.onAwait { it }
        }.also { coroutineContext.cancelChildren() }
    }

    // A command that failed on its own leaves the camera without the held index it took the place of.
    private fun applyReplaced(request: Request, replaced: Camera?) {
        if (replaced == null || request.run.end != null || isReleased() || replaced !== camera()) return
        applyNow(replaced)
    }

    // CameraX drops an exposure request sent while it is still configuring the session it binds, without
    // completing or failing it; the session's first capture result shows it configured. Null once [request]'s
    // session streams; Busy when a bind ended its run first, and Closed when a stop or the release did.
    private suspend fun awaitStreaming(request: Request, operation: CameraOperation): CameraKResult<Unit>? {
        results.first { it.run !== request.run || it.index != null }
        if (isReleased()) return AndroidCameraEngine.CLOSED
        return when (request.run.end) {
            null -> null
            RunEnd.REBIND -> CameraKResult.Failure(CameraKError.Busy(operation))
            RunEnd.STOP -> AndroidCameraEngine.CLOSED
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

    private class Results(
        val run: Run,
        val session: Any?,
        val ignored: Any?,
        val count: Long,
        val index: Int?,
        val counting: Boolean,
        val afState: Int? = null,
    ) {
        fun counts(from: Any): Boolean = counting && from != ignored
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
        const val UNLOCKED_FOCUS_RESULT_MS = 1_000L
    }
}
