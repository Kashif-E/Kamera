package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
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
 * once more at the camera's own mode. [own] is told which grabber the caller must release.
 */
internal suspend fun GrabberSource.open(
    device: LensInfo,
    own: (FrameGrabber?) -> Unit,
    release: (FrameGrabber) -> Unit,
): OpenedDevice = TODO("D1'")

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
