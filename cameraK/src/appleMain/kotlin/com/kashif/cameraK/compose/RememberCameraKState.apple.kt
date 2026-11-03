package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder

/** iOS implementation of [rememberCameraKState], over the production constructor. */
@Composable
actual fun rememberCameraKState(
    config: CameraConfiguration,
    setupPlugins: suspend (CameraKStateHolder) -> Unit,
): State<CameraKState> {
    val holder = rememberCameraKStateHolder(config, emptyList(), LocalLifecycleOwner.current)
    LaunchedEffect(holder) { setupPlugins(holder) }
    return holder.cameraState.collectAsStateWithLifecycle()
}

/** iOS implementation of [rememberCameraKStateHolder]. */
@Composable
actual fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
): CameraKStateHolder = rememberLifecycleHolder(config, lifecycleOwner) {
    CameraKStateHolder(config, CameraKPlatformContext(), plugins)
}
