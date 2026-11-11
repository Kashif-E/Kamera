@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * The composition's context; the holder keeps only its application context. The camera's own
 * lifecycle owner lives in the engine, so the host's lifecycle reaches the camera through the
 * holder's start and stop: it closes in the background, as in 1.x.
 */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext(LocalContext.current)

@Composable
internal actual fun rememberHostRecreation(): () -> Boolean {
    val activity = LocalActivity.current
    return remember(activity) { { activity?.isChangingConfigurations == true } }
}
