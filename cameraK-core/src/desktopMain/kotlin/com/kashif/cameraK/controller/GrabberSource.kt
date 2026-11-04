package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.platform.DesktopGrabberFactory
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber

/** Where a grab loop gets its grabber for a device. */
internal interface GrabberSource {
    /** What the per-device lock is keyed on for [device]. */
    fun deviceKey(device: LensInfo): Any

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
 * throws or is cancelled, as `start()` is called on it: an open cancelled before that leaves nothing
 * to release. The first grabber of a retried open is released here. Each `start()` runs
 * interruptibly, so cancelling the open interrupts it. Missing natives are not retried.
 */
internal suspend fun GrabberSource.open(
    device: LensInfo,
    own: (FrameGrabber?) -> Unit,
    release: (FrameGrabber) -> Unit,
): OpenedDevice {
    val constrained = create(device, constrained = true).also(::prepare)
    try {
        blockingCall {
            own(constrained)
            constrained.start()
        }
        return OpenedDevice(constrained, relaxed = false)
    } catch (e: Exception) {
        if (e is CancellationException || !canRelax || JavaCvErrorMapper.isNativeLibraryMissing(e)) throw e
        CameraKLogger.w(TAG, "Opening the camera at CameraK's size and frame rate failed; retrying at its own mode", e)
        own(null)
        release(constrained)
        val relaxed = create(device, constrained = false).also(::prepare)
        try {
            blockingCall {
                own(relaxed)
                relaxed.start()
            }
        } catch (retry: Exception) {
            if (retry !is CancellationException) retry.addSuppressed(e)
            throw retry
        }
        return OpenedDevice(relaxed, relaxed = true)
    }
}

// FFmpeg reads macOS cameras through AVFoundation; the 1.x loop set the format on every FFmpeg grabber.
private fun prepare(grabber: FrameGrabber) {
    if (grabber is FFmpegFrameGrabber && currentOperatingSystem() == OperatingSystem.MAC) {
        grabber.format = "avfoundation"
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

/**
 * A grabber the app built itself (the 1.x `customGrabber`): its own device, opened as it is, with
 * no CameraK size or frame rate to drop on a retry.
 */
internal class CustomGrabberSource(private val grabber: FrameGrabber) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = grabber
    override val canRelax: Boolean get() = false

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = grabber
}

/**
 * The system's default camera, opened as the 1.x controller opened it: at [targetResolution] (720p
 * when null) and CameraK's frame rate, or at the camera's own mode on the retry. Its lock is keyed
 * on the default entry's id. The 1.x constructors and `CameraGrabber.start` use it; the bridge's
 * engine selects among enumerated devices ([EnumeratedDeviceSource]). Tests replace it through
 * [defaultCameraOverride].
 */
internal class DefaultDeviceSource(private val targetResolution: Pair<Int, Int>?) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = device.id
    override val canRelax: Boolean get() = true

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = defaultCameraOverride?.invoke()
        ?: createDefaultGrabber(
            currentOperatingSystem(),
            if (constrained) targetResolution ?: DEFAULT_CAMERA_RESOLUTION else null,
        )
}

/**
 * The enumerated cameras of [devices], each opened by its `LensInfo.id` at [targetResolution] (720p
 * when null) and CameraK's frame rate, or at the camera's own mode on the retry. The lock is keyed
 * on the id (§2.4 desktop). Tests that reach a real camera get [defaultCameraOverride] instead.
 */
internal class EnumeratedDeviceSource(
    private val devices: DesktopDevices,
    private val targetResolution: Pair<Int, Int>?,
) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = device.id

    override val canRelax: Boolean get() = true

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = defaultCameraOverride?.invoke()
        ?: devices.open(device.id, if (constrained) targetResolution ?: DEFAULT_CAMERA_RESOLUTION else null)
}

/**
 * The app's [DesktopGrabberFactory]: every open, watchdog reopens included, asks it for the selected
 * device's id. Its grabber is opened as the app built it, like the 1.x `customGrabber`, so there is
 * no CameraK size or frame rate to drop on a retry. The lock is keyed on the id.
 */
@OptIn(CameraKPlatformApi::class)
internal class FactoryGrabberSource(private val factory: DesktopGrabberFactory) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = device.id

    override val canRelax: Boolean get() = false

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = factory.create(device.id)
}
