@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.InternalCameraKApi

/** iOS needs no platform context. */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext()

@Composable
internal actual fun CameraKStateHolder.collectCameraState(): State<CameraKState> =
    cameraState.collectAsStateWithLifecycle()
