package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import org.bytedeco.javacv.FrameGrabber

/** Where a grab loop gets its grabber for a device. */
internal interface GrabberSource {
    /** What the per-device lock is keyed on. */
    val deviceKey: Any

    /** Whether a failed open is retried once without CameraK's size and frame rate (§4.1 row 3). */
    val canRelax: Boolean

    /** A new grabber for [device]; [constrained] asks for CameraK's size and frame rate. */
    fun create(device: LensInfo, constrained: Boolean): FrameGrabber
}

/** A started grabber, and whether it opened only at the camera's own mode. */
internal class OpenedDevice(val grabber: FrameGrabber, val relaxed: Boolean)

/**
 * Opens [device] at CameraK's size and frame rate and, if that fails and the source allows it,
 * once more at the camera's own mode (§4.1 row 3: CameraGrabber set both on every open, so a camera
 * without that mode never opened). [own] is told which grabber the caller must release if this
 * throws or is cancelled; the first grabber of a retried open is released here. Each `start()`
 * runs interruptibly, so cancelling the open interrupts it. Missing natives are not retried.
 */
internal suspend fun GrabberSource.open(
    device: LensInfo,
    own: (FrameGrabber?) -> Unit,
    release: (FrameGrabber) -> Unit,
): OpenedDevice {
    val constrained = create(device, constrained = true).also(own)
    try {
        blockingCall { constrained.start() }
        return OpenedDevice(constrained, relaxed = false)
    } catch (e: Exception) {
        if (e is CancellationException || !canRelax || JavaCvErrorMapper.isNativeLibraryMissing(e)) throw e
        CameraKLogger.w(TAG, "Opening the camera at CameraK's size and frame rate failed; retrying at its own mode", e)
        own(null)
        release(constrained)
        val relaxed = create(device, constrained = false).also(own)
        try {
            blockingCall { relaxed.start() }
        } catch (retry: Exception) {
            if (retry !is CancellationException) retry.addSuppressed(e)
            throw retry
        }
        return OpenedDevice(relaxed, relaxed = true)
    }
}

/**
 * Runs a blocking JavaCV [call] interruptibly: cancelling the caller interrupts it. The call's own
 * throwable is rethrown as the same instance; thrown out of runInterruptible it would arrive as
 * kotlinx-coroutines' recovered copy, and the error mapper and `CameraKError.cause` keep the
 * platform's own.
 */
internal suspend fun <T> blockingCall(call: () -> T): T = runInterruptible {
    try {
        Result.success(call())
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}.getOrThrow()

private const val TAG = "CameraK"

/** A grabber the app built itself (the 1.x `customGrabber`): its own device, opened as it is. */
internal class CustomGrabberSource(private val grabber: FrameGrabber) : GrabberSource {
    override val deviceKey: Any get() = TODO("D1'")
    override val canRelax: Boolean get() = TODO("D1'")

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = TODO("D1'")
}

/** The system's default camera, opened as the 1.x controller opened it. */
internal class DefaultDeviceSource(private val targetResolution: Pair<Int, Int>?) : GrabberSource {
    override val deviceKey: Any get() = TODO("D1'")
    override val canRelax: Boolean get() = TODO("D1'")

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = TODO("D1'")
}
