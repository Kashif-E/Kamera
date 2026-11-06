@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

/** The desktop camera, with no grabber factory: the holder opens the enumerated devices. */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext()
