package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder

/** Desktop implementation of [rememberCameraKState], over the production constructor. */
@Composable
actual fun rememberCameraKState(
    config: CameraConfiguration,
    setupPlugins: suspend (CameraKStateHolder) -> Unit,
): State<CameraKState> {
    val holder = rememberCameraKStateHolder(config, emptyList(), LocalLifecycleOwner.current)
    LaunchedEffect(holder) { setupPlugins(holder) }
    return holder.cameraState.collectAsState()
}

/** Desktop implementation of [rememberCameraKStateHolder]: the desktop camera, with no platform context to pass. */
@Composable
actual fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
): CameraKStateHolder = rememberLifecycleHolder(config, lifecycleOwner) {
    CameraKStateHolder(config, CameraKPlatformContext(), plugins)
}
