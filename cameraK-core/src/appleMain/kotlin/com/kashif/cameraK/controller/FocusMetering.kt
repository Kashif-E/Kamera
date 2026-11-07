package com.kashif.cameraK.controller

import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureExposureMode
import platform.AVFoundation.AVCaptureExposureModeAutoExpose
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFocusMode
import platform.AVFoundation.AVCaptureFocusModeAutoFocus
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/** A focus is settled if the camera is not adjusting this long after the point is set. */
private const val SETTLE_CHECK_MS = 150L

/** A focus still adjusting this long after the point is set has timed out. */
private const val FOCUS_TIMEOUT_MS = 2_000L

/**
 * Focus, metering and exposure compensation on an iOS engine's camera (w13 Z-focus-ios). Every device
 * call runs on the session queue under the configuration lock.
 *
 * - [focusAt] sets the point of interest and the one-shot modes for what the camera supports, with
 *   subject-area monitoring on. The point is the preview layer's conversion of the content point, or,
 *   without a usable layer, [contentPointToDevicePoint] from the frame. The focus has settled when
 *   `isAdjustingFocus` turns false after having been true, or is false [SETTLE_CHECK_MS] after the set;
 *   still adjusting at [FOCUS_TIMEOUT_MS] is Timeout. A camera that meters exposure only succeeds once
 *   the configuration is committed. A subject-area change returns to continuous focus and exposure at
 *   the centre, iOS's own return to its metering.
 * - [setExposureCompensation] sets the bias of the index and returns once its completion has run, with
 *   the index the device's bias reads back as already published (rule 5).
 * - The last index set is applied again after every start and camera switch, clamped into the camera's
 *   range, and dropped on a camera without one (rule 6). A focus point is never re-applied.
 *
 * A newer request, a reset or a camera switch supersedes one in flight with Busy; stop and close answer
 * it Closed at once.
 */
internal class FocusMetering(
    private val generations: EngineGenerations,
    private val custom: CustomCameraController,
    private val frameRotationDegrees: () -> Int,
    private val publishHardware: () -> Unit,
) {
    /** The index applied to each camera bound: the last one set, or the start's. */
    @Volatile
    var desiredExposure: Int = 0

    private val pendingFocus = atomic<Pending?>(null)
    private val pendingExposure = atomic<Pending?>(null)

    // The camera whose subject area is observed, and the observation. Session queue only.
    private var subjectAreaDevice: CaptureDeviceOps? = null
    private var subjectArea: AutoCloseable? = null

    suspend fun focusAt(point: NormalizedPoint): CameraKResult<Unit> {
        generations.notOpen()?.let { return it }
        val layerPoint = previewDevicePoint(point)
        return onQueue("focusing at a point", CameraOperation.FOCUS_POINT) { focusOnQueue(it, point, layerPoint) }
    }

    suspend fun setExposureCompensation(index: Int): CameraKResult<Unit> {
        generations.notOpen()?.let { return it }
        return onQueue("setting the exposure compensation", CameraOperation.EXPOSURE_COMPENSATION) {
            exposureOnQueue(it, index)
        }
    }

    suspend fun resetFocusAndExposure(): CameraKResult<Unit> {
        generations.notOpen()?.let { return it }
        return onQueue("resetting focus and exposure", CameraOperation.FOCUS_POINT, ::resetOnQueue)
    }

    /**
     * Session queue, once [device] is the session's camera after a start or a switch: what was in flight
     * is superseded, and the desired index applied, clamped into its range, or dropped without one.
     */
    fun onBound(device: CaptureDeviceOps) {
        pendingFocus.getAndSet(null)?.supersede()
        pendingExposure.getAndSet(null)?.supersede()
        if (subjectAreaDevice !== device) stopObservingSubjectArea()
        val range = device.exposureIndexes
        if (range == null) {
            desiredExposure = 0
            return
        }
        val target = desiredExposure.coerceIn(range)
        if (device.exposureIndex == target) return
        withLock(device, "re-applying the exposure compensation") {
            device.setExposureTargetBias(device.exposureTargetBiasOf(target)) {
                afterCompletion("an exposure compensation report") {
                    if (custom.currentDevice === device && generations.isStartingOrLive) publishHardware()
                }
            }
        }
    }

    /** Stop or close, from any thread: what is in flight answers Closed at once. */
    fun end() {
        pendingFocus.getAndSet(null)?.finish(ENGINE_CLOSED)
        pendingExposure.getAndSet(null)?.finish(ENGINE_CLOSED)
        custom.afterQueuedSessionWork {
            platformCallback("ending the subject-area observation") { stopObservingSubjectArea() }
        }
    }

    private fun focusOnQueue(pending: Pending, point: NormalizedPoint, layerPoint: PointOfInterest?) {
        val device = custom.currentDevice ?: return pending.finish(engineFailure(CameraKError.NotReady))
        val focus = device.isFocusPointOfInterestSupported
        val exposure = device.isExposurePointOfInterestSupported
        if (!focus && !exposure) return pending.finish(unsupported(CameraFeature.FOCUS_POINT))
        pendingFocus.getAndSet(pending)?.supersede()
        val target = layerPoint ?: contentPointToDevicePoint(
            point,
            mirrored = device.isFront,
            rotationDegrees = frameRotationDegrees(),
            aspectRatio = custom.aspectRatio,
            format = device.activeFormatDimensions,
        )
        val refused = withLock(device, "focusing at a point") {
            meter(device, target, AVCaptureFocusModeAutoFocus, AVCaptureExposureModeAutoExpose, monitor = true)
        }
        if (refused != null) return settleFocus(pending, hardwareFailure(refused))
        observeSubjectArea(device)
        if (!focus) return settleFocus(pending, SUCCESS)

        pending.observe(
            device.observeAdjustingFocus {
                afterCompletion("a focus report") {
                    if (device.isAdjustingFocus) {
                        pending.adjusted = true
                    } else if (pending.adjusted) {
                        settleFocus(pending, SUCCESS)
                    }
                }
            },
        )
        custom.onSessionQueueAfter(SETTLE_CHECK_MS) {
            platformCallback("checking the focus") {
                if (device.isAdjustingFocus) pending.adjusted = true else settleFocus(pending, SUCCESS)
            }
        }
        custom.onSessionQueueAfter(FOCUS_TIMEOUT_MS) {
            platformCallback("timing the focus out") {
                settleFocus(pending, engineFailure(CameraKError.Timeout(CameraOperation.FOCUS_POINT)))
            }
        }
    }

    private fun exposureOnQueue(pending: Pending, index: Int) {
        val device = custom.currentDevice ?: return pending.finish(engineFailure(CameraKError.NotReady))
        val range = device.exposureIndexes ?: return pending.finish(unsupported(CameraFeature.EXPOSURE_COMPENSATION))
        // The holder has checked the index; AVFoundation raises an exception nothing can catch outside the range.
        val target = index.coerceIn(range)
        setBias(device, target, pending) { desiredExposure = target }
    }

    private fun resetOnQueue(pending: Pending) {
        val device = custom.currentDevice ?: return pending.finish(engineFailure(CameraKError.NotReady))
        val range = device.exposureIndexes
        if (!device.metersAtAPoint && range == null) return pending.finish(unsupported(CameraFeature.FOCUS_POINT))
        pendingFocus.getAndSet(null)?.supersede()
        stopObservingSubjectArea()
        val refused = withLock(device, "resetting focus and exposure") {
            meter(
                device,
                CENTRE_OF_INTEREST,
                AVCaptureFocusModeContinuousAutoFocus,
                AVCaptureExposureModeContinuousAutoExposure,
                monitor = false,
            )
        }
        if (refused != null) return pending.finish(hardwareFailure(refused))
        if (range == null) return pending.finish(SUCCESS)
        setBias(device, 0, pending) { desiredExposure = 0 }
    }

    /**
     * Session queue: [index]'s bias set on [device] for [pending], which supersedes the exposure request in
     * flight. Once the completion runs, unless something superseded it, [onApplied] runs, the read-back
     * is published and [pending] succeeds.
     */
    private fun setBias(device: CaptureDeviceOps, index: Int, pending: Pending, onApplied: () -> Unit) {
        pendingExposure.getAndSet(pending)?.supersede()
        val refused = withLock(device, "setting the exposure compensation") {
            device.setExposureTargetBias(device.exposureTargetBiasOf(index)) {
                afterCompletion("an exposure compensation report") {
                    if (pendingExposure.compareAndSet(pending, null)) {
                        onApplied()
                        publishHardware()
                        pending.finish(SUCCESS)
                    }
                }
            }
        }
        if (refused != null) settleExposure(pending, hardwareFailure(refused))
    }

    // Under the configuration lock: the point and modes for what [device] supports, and subject-area monitoring.
    private fun meter(
        device: CaptureDeviceOps,
        point: PointOfInterest,
        focusMode: AVCaptureFocusMode,
        exposureMode: AVCaptureExposureMode,
        monitor: Boolean,
    ) {
        if (device.isFocusPointOfInterestSupported) {
            device.setFocusPointOfInterest(point)
            if (device.isFocusModeSupported(focusMode)) device.setFocusMode(focusMode)
        }
        if (device.isExposurePointOfInterestSupported) {
            device.setExposurePointOfInterest(point)
            if (device.isExposureModeSupported(exposureMode)) device.setExposureMode(exposureMode)
        }
        device.setSubjectAreaChangeMonitoringEnabled(monitor)
    }

    // Session queue: AVFoundation's subject-area change returns the camera to continuous metering at the centre.
    private fun observeSubjectArea(device: CaptureDeviceOps) {
        if (subjectAreaDevice === device) return
        stopObservingSubjectArea()
        subjectAreaDevice = device
        subjectArea = device.observeSubjectAreaChange {
            afterCompletion("a subject-area change") {
                if (subjectAreaDevice !== device || custom.currentDevice !== device) return@afterCompletion
                stopObservingSubjectArea()
                withLock(device, "returning to continuous metering") {
                    meter(
                        device,
                        CENTRE_OF_INTEREST,
                        AVCaptureFocusModeContinuousAutoFocus,
                        AVCaptureExposureModeContinuousAutoExposure,
                        monitor = false,
                    )
                }
            }
        }
    }

    private fun stopObservingSubjectArea() {
        subjectArea?.close()
        subjectArea = null
        subjectAreaDevice = null
    }

    /** Runs [block] holding [device]'s configuration lock; returns what refused the lock, logged here, or null. */
    private inline fun withLock(device: CaptureDeviceOps, operation: String, block: () -> Unit): Throwable? {
        val refused = device.lockForConfiguration()
        if (refused != null) {
            CameraKLogger.e(TAG, "could not lock the camera for $operation", refused)
            return refused
        }
        try {
            block()
        } finally {
            device.unlockForConfiguration()
        }
        return null
    }

    // A completion or report from AVFoundation, on whatever thread it arrives, handled on the session queue.
    private fun afterCompletion(what: String, block: () -> Unit) {
        platformCallback(what) { custom.afterQueuedSessionWork { platformCallback(what, block) } }
    }

    private fun settleFocus(pending: Pending, result: CameraKResult<Unit>) {
        pendingFocus.compareAndSet(pending, null)
        pending.finish(result)
    }

    private fun settleExposure(pending: Pending, result: CameraKResult<Unit>) {
        pendingExposure.compareAndSet(pending, null)
        pending.finish(result)
    }

    /**
     * The point of interest the preview layer gives for [point], read on main, where the layer lives;
     * null without a usable layer.
     */
    private suspend fun previewDevicePoint(point: NormalizedPoint): PointOfInterest? {
        if (NSThread.isMainThread) return layerPoint(point)
        return suspendCancellableCoroutine { continuation ->
            dispatch_async(dispatch_get_main_queue()) { continuation.resume(layerPoint(point)) }
        }
    }

    private fun layerPoint(point: NormalizedPoint): PointOfInterest? = try {
        custom.previewDevicePoint(point.x, point.y)
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "converting the point through the preview layer failed; mapping it from the frame", e)
        null
    }

    private suspend fun onQueue(
        what: String,
        operation: CameraOperation,
        block: (Pending) -> Unit,
    ): CameraKResult<Unit> = suspendCancellableCoroutine { continuation ->
        val pending = Pending(operation, continuation)
        continuation.invokeOnCancellation { pending.abandon() }
        custom.afterQueuedSessionWork {
            try {
                generations.notOpen()?.let { return@afterQueuedSessionWork pending.finish(it) }
                block(pending)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "$what failed", e)
                pendingFocus.compareAndSet(pending, null)
                pendingExposure.compareAndSet(pending, null)
                pending.finish(engineFailure(CameraKError.Internal(e)))
            }
        }
    }

    private fun hardwareFailure(refused: Throwable) =
        engineFailure(CameraKError.Hardware((refused as? NSErrorException)?.error?.code?.toInt(), refused))

    private fun unsupported(feature: CameraFeature) = engineFailure(CameraKError.Unsupported(feature))

    /** One request's caller, answered once; its focus observation ends with it. */
    private class Pending(
        private val operation: CameraOperation,
        private val continuation: CancellableContinuation<CameraKResult<Unit>>,
    ) {
        private val done = atomic(false)
        private val observation = atomic<AutoCloseable?>(null)

        /** Whether the camera was seen focusing since the point was set. Session queue only. */
        var adjusted = false

        /** Ends [focus] with this request; at once if it has already been answered. */
        fun observe(focus: AutoCloseable) {
            observation.value = focus
            if (done.value) observation.getAndSet(null)?.close()
        }

        fun finish(result: CameraKResult<Unit>) {
            if (!done.compareAndSet(expect = false, update = true)) return
            observation.getAndSet(null)?.close()
            continuation.resume(result)
        }

        fun supersede() = finish(engineFailure(CameraKError.Busy(operation)))

        fun abandon() {
            done.value = true
            observation.getAndSet(null)?.close()
        }
    }

    private companion object {
        val SUCCESS = CameraKResult.Success(Unit)
    }
}
