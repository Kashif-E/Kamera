package com.kashif.cameraK.platform

import com.kashif.cameraK.utils.CameraKPlatformApi
import org.bytedeco.javacv.FrameGrabber

/** Supplies the `FrameGrabber` for a device, for an IP stream or any grabber CameraK does not build itself. */
@CameraKPlatformApi
public fun interface DesktopGrabberFactory {
    /**
     * Called off the main thread on every open, watchdog reopens included, with the selected
     * device's `LensInfo.id`. The engine starts, stops and releases the grabber it returns; the
     * factory never sees it again.
     */
    public fun create(deviceId: String): FrameGrabber
}
