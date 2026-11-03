package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder

/**
 * Android implementation of [rememberCameraKState], over the production constructor. The camera's
 * own lifecycle owner lives in the engine, so the host's lifecycle reaches the camera through the
 * holder's start and stop: it closes in the background, as in 1.x.
 */
@Composable
actual fun rememberCameraKState(
    config: CameraConfiguration,
    setupPlugins: suspend (CameraKStateHolder) -> Unit,
): State<CameraKState> {
    val holder = rememberCameraKStateHolder(config, emptyList(), LocalLifecycleOwner.current)
    LaunchedEffect(holder) { setupPlugins(holder) }
    return holder.cameraState.collectAsStateWithLifecycle()
}

/** Android implementation of [rememberCameraKStateHolder]: the holder keeps the application context only. */
@Composable
actual fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
): CameraKStateHolder {
    val context = LocalContext.current
    return rememberLifecycleHolder(config, lifecycleOwner) {
        CameraKStateHolder(config, CameraKPlatformContext(context), plugins)
    }
}
