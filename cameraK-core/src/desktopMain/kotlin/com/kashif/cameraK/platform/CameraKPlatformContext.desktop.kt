@file:OptIn(CameraKPlatformApi::class)

package com.kashif.cameraK.platform

import com.kashif.cameraK.utils.CameraKPlatformApi

/**
 * Desktop needs nothing by default: the engine enumerates the devices itself (V4L2 nodes on Linux,
 * AVFoundation on macOS, FFmpeg dshow on Windows) and lists them as the capabilities' lenses. An
 * app that brings its own grabbers, such as an IP stream, passes a [DesktopGrabberFactory]. The
 * engine still enumerates then, because enumeration opens nothing; the factory replaces only how a
 * device is opened, and receives the selected device's `LensInfo.id`. With no local camera the
 * engine lists one default entry for the factory to map.
 *
 * Two constructors rather than a defaulted parameter: a constructor whose signature names the
 * opted-in type would need the opt-in at every call, even one that omits the argument.
 */
public actual class CameraKPlatformContext {
    internal val grabberFactory: DesktopGrabberFactory?

    public constructor() {
        grabberFactory = null
    }

    @CameraKPlatformApi
    public constructor(grabberFactory: DesktopGrabberFactory) {
        this.grabberFactory = grabberFactory
    }
}
