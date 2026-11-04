package com.kashif.cameraK.platform

import com.kashif.cameraK.utils.CameraKPlatformApi
import org.bytedeco.javacv.FrameGrabber

/**
 * Supplies the `FrameGrabber` for a device, for an IP stream or any grabber CameraK does not build
 * itself. The engine still enumerates the machine's cameras and selects among them as it would
 * without a factory; the factory only opens the selected one, and may map its id to any source.
 * Selection names what the engine's capabilities list, so the factory cannot add ids of its own.
 * On a machine with no camera the list is one default entry, whose id is the empty string.
 */
@CameraKPlatformApi
public fun interface DesktopGrabberFactory {
    /**
     * Called off the main thread on every open, watchdog reopens and camera switches included, with
     * the selected device's `LensInfo.id`. The grabber is started as returned, with no size or
     * frame rate set by CameraK. The engine starts, stops and releases the grabber it returns; the
     * factory never sees it again.
     */
    public fun create(deviceId: String): FrameGrabber
}
