package com.kashif.cameraK.plugin

import android.content.Context
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * The application context [CameraKPlatformContext] wraps, for a first-party plugin that reaches
 * storage itself: the media saver's MediaStore writes. Apps pass the platform context and never read it.
 */
@InternalCameraKApi
public val CameraKPlatformContext.androidApplicationContext: Context get() = applicationContext
