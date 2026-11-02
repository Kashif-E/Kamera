package com.kashif.cameraK.builder

import android.content.Context
import androidx.lifecycle.LifecycleOwner

/**
 * Creates an Android-specific [CameraControllerBuilder].
 *
 * @param context The Android [Context], typically an Activity or Application context.
 * @param lifecycleOwner The [LifecycleOwner], usually the hosting Activity or Fragment.
 * @return An instance of [CameraControllerBuilder].
 */
fun createAndroidCameraControllerBuilder(context: Context, lifecycleOwner: LifecycleOwner): CameraControllerBuilder =
    AndroidCameraControllerBuilder(context, lifecycleOwner)
