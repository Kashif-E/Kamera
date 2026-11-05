package com.kashif.cameraK.platform

import android.content.Context
import com.kashif.cameraK.utils.InternalCameraKApi

/** The application context [CameraKPlatformContext] wraps, for the first-party plugins that reach storage. */
@InternalCameraKApi
public val CameraKPlatformContext.androidApplicationContext: Context get() = applicationContext
