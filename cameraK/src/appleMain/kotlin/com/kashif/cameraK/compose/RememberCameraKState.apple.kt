@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

/** iOS needs no platform context. */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext()
