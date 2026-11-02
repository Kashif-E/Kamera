package com.kashif.cameraK.platform

import android.content.Context

/** Wraps the application context of [context], so an Activity passed here is never retained. */
public actual class CameraKPlatformContext(context: Context) {
    internal val applicationContext: Context = context.applicationContext
}
