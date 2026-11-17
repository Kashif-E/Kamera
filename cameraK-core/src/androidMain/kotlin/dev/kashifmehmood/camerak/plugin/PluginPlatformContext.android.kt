package dev.kashifmehmood.camerak.plugin

import android.content.Context
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

/**
 * The application context [CameraKPlatformContext] wraps, for a first-party plugin that reaches
 * storage itself: the media saver's MediaStore writes. Apps pass the platform context and never read it.
 */
@InternalCameraKApi
public val CameraKPlatformContext.androidApplicationContext: Context get() = applicationContext
