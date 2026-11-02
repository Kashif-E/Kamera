@file:OptIn(CameraKPlatformApi::class)

package com.kashif.cameraK.platform

import com.kashif.cameraK.utils.CameraKPlatformApi

/**
 * Desktop needs nothing by default: the engine enumerates the devices itself. An app that brings its
 * own grabbers passes a [DesktopGrabberFactory].
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
