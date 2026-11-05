@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * The composition's context; the holder keeps only its application context. The camera's own
 * lifecycle owner lives in the engine, so the host's lifecycle reaches the camera through the
 * holder's start and stop: it closes in the background, as in 1.x.
 */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext(LocalContext.current)

@Composable
internal actual fun CameraKStateHolder.collectCameraState(): State<CameraKState> =
    cameraState.collectAsStateWithLifecycle()
