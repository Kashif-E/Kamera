package com.kashif.cameraK.controller

import android.util.Range
import android.util.Rational
import androidx.camera.core.CameraControl
import androidx.camera.core.ExposureState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.concurrent.futures.ResolvableFuture
import com.google.common.util.concurrent.ListenableFuture

/**
 * What a fake camera meters with: the [FocusMeteringAction] flags it supports at a point (none for a
 * camera that meters at no point) and its exposure compensation range and step (no range for a camera
 * without compensation).
 */
internal class FakeMetering(
    val flags: Set<Int> = emptySet(),
    val exposureRange: IntRange? = null,
    val exposureStep: Rational = Rational(1, 3),
) {
    companion object {
        /** A camera that meters AF, AE and AWB at a point and takes exposure indexes -12 to 12, in sixths of an EV. */
        val FULL = FakeMetering(
            setOf(FocusMeteringAction.FLAG_AF, FocusMeteringAction.FLAG_AE, FocusMeteringAction.FLAG_AWB),
            exposureRange = -12..12,
            exposureStep = Rational(1, 6),
        )

        /** A fixed-focus camera: it meters AE and AWB at a point, and takes exposure indexes -6 to 6. */
        val FIXED_FOCUS = FakeMetering(
            setOf(FocusMeteringAction.FLAG_AE, FocusMeteringAction.FLAG_AWB),
            exposureRange = -6..6,
        )
    }
}

/** A focus and metering action the camera accepted, and the future CameraX returned for it. */
internal class FocusRequest(val action: FocusMeteringAction, val future: ResolvableFuture<FocusMeteringResult>) {
    val flags: Set<Int>
        get() = buildSet {
            if (action.meteringPointsAf.isNotEmpty()) add(FocusMeteringAction.FLAG_AF)
            if (action.meteringPointsAe.isNotEmpty()) add(FocusMeteringAction.FLAG_AE)
            if (action.meteringPointsAwb.isNotEmpty()) add(FocusMeteringAction.FLAG_AWB)
        }

    /** CameraX reports the action done: focused or not. */
    fun complete(focused: Boolean) {
        future.set(FocusMeteringResult.create(focused))
    }
}

/**
 * A fake camera's focus, metering and exposure control, answering as CameraX 1.5.3's
 * FocusMeteringControl and ExposureControl do: a request on a camera that is not open fails with
 * `OperationCanceledException("Camera is not active.")`; a newer focus request, or a cancel, fails the
 * pending one with `OperationCanceledException`, as a newer exposure request fails the pending one; the
 * camera's closing fails every pending request and returns the exposure index to 0. The exposure state
 * takes a request's index at once, as CameraX's does, and [applies] says which index the device takes.
 */
internal class FakeMeteringControl(private val metering: FakeMetering, private val isOpen: () -> Boolean) {
    /** Each focus and metering action the camera accepted, oldest first. */
    val focusRequests = mutableListOf<FocusRequest>()

    /** The exposure indexes the camera accepted, oldest first. */
    val exposureRequests = mutableListOf<Int>()

    /** How often focus and metering was cancelled. */
    var cancelCalls = 0
        private set

    /** When true, a focus action waits for [FocusRequest.complete]; otherwise it completes at once, focused. */
    var holdFocus = false

    /** When true, an exposure request waits for [completeExposure]; otherwise it completes at once. */
    var holdExposure = false

    /** When set, every focus action fails with it, as CameraX fails one the capture session rejects. */
    var focusFailure: Throwable? = null

    /** The index the device applies for a requested one: by default the request. */
    var applies: (Int) -> Int = { it }

    /**
     * When true, the camera's closing leaves what is pending to the test, as CameraX's camera thread can
     * finish or fail a request after a stop, a start or a switch has gone on: [failPending] fails it as
     * the close would, [completeExposure] and [FocusRequest.complete] finish it.
     */
    var closeLeavesPending = false

    private var exposureIndex = 0
    private val pendingExposure = mutableListOf<ResolvableFuture<Int>>()

    val exposureState: ExposureState = object : ExposureState {
        override fun getExposureCompensationIndex(): Int = exposureIndex

        override fun getExposureCompensationRange(): Range<Int> =
            metering.exposureRange?.let { Range(it.first, it.last) } ?: Range(0, 0)

        override fun getExposureCompensationStep(): Rational =
            if (metering.exposureRange != null) metering.exposureStep else Rational.ZERO

        override fun isExposureCompensationSupported(): Boolean = metering.exposureRange != null
    }

    /** Whether the camera meters with any of [action]'s points, as CameraX answers; it drops the others. */
    fun isFocusMeteringSupported(action: FocusMeteringAction): Boolean =
        FocusRequest(action, ResolvableFuture.create()).flags.any { it in metering.flags }

    fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult> {
        if (!isOpen()) return failed(notActive())
        if (!isFocusMeteringSupported(action)) {
            return failed(IllegalArgumentException("None of the specified MeteringPoints is supported on this camera."))
        }
        supersedeFocus("Cancelled by another startFocusAndMetering()")
        val request = FocusRequest(action, ResolvableFuture.create())
        focusRequests += request
        focusFailure?.let { request.future.setException(it) }
        if (!holdFocus && focusFailure == null) request.complete(focused = true)
        return request.future
    }

    fun cancelFocusAndMetering(): ListenableFuture<Void> {
        if (!isOpen()) return failed(notActive())
        cancelCalls++
        supersedeFocus("Cancelled by cancelFocusAndMetering()")
        return ResolvableFuture.create<Void>().apply { set(null) }
    }

    fun setExposureCompensationIndex(index: Int): ListenableFuture<Int> {
        if (!isOpen()) return failed(notActive())
        val range = metering.exposureRange
            ?: return failed(IllegalArgumentException("ExposureCompensation is not supported"))
        if (index !in range) return failed(IllegalArgumentException("ExposureCompensation $index is out of range"))
        val superseded = CameraControl.OperationCanceledException("Cancelled by another setExposureCompensationIndex()")
        pendingExposure.toList().forEach { it.setException(superseded) }
        pendingExposure.clear()
        exposureRequests += index
        exposureIndex = applies(index)
        val future = ResolvableFuture.create<Int>()
        if (holdExposure) pendingExposure += future else future.set(exposureIndex)
        return future
    }

    /** CameraX reports the pending exposure request applied. */
    fun completeExposure() {
        pendingExposure.toList().forEach { it.set(exposureIndex) }
        pendingExposure.clear()
    }

    /** The camera closed: CameraX fails what is pending and returns the exposure index to 0. */
    fun onClosed() {
        exposureIndex = 0
        if (!closeLeavesPending) failPending()
    }

    /** CameraX fails what is pending, as the camera's closing does. */
    fun failPending() {
        supersedeFocus("Camera is not active.")
        pendingExposure.toList().forEach { it.setException(notActive()) }
        pendingExposure.clear()
    }

    private fun supersedeFocus(message: String) {
        focusRequests.filter { !it.future.isDone }.forEach {
            it.future.setException(CameraControl.OperationCanceledException(message))
        }
    }

    private fun notActive() = CameraControl.OperationCanceledException("Camera is not active.")

    private fun <T> failed(cause: Throwable): ListenableFuture<T> = ResolvableFuture.create<T>().apply {
        setException(cause)
    }
}
