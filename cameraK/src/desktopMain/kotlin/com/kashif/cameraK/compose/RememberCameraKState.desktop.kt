@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.InternalCameraKApi

/** The desktop camera, with no grabber factory: the holder opens the enumerated devices. */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext()

@Composable
internal actual fun CameraKStateHolder.collectCameraState(): State<CameraKState> = cameraState.collectAsState()
