@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * Android implementation of [rememberCameraKState], over the production constructor. The camera's
 * own lifecycle owner lives in the engine, so the host's lifecycle reaches the camera through the
 * holder's start and stop: it closes in the background, as in 1.x. As in 1.x, the holder is
 * remembered in this composition and closes with it, so [setupPlugins] runs once per holder.
 */
@Composable
actual fun rememberCameraKState(
    config: CameraConfiguration,
    setupPlugins: suspend (CameraKStateHolder) -> Unit,
): State<CameraKState> {
    val holder = rememberPlatformHolder(config, emptyList(), LocalLifecycleOwner.current, viewModelStoreOwner = null)
    LaunchedEffect(holder) { setupPlugins(holder) }
    return holder.cameraState.collectAsStateWithLifecycle()
}

/** Android implementation of [rememberCameraKStateHolder]: the holder keeps the application context only. */
@Composable
actual fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
): CameraKStateHolder = rememberPlatformHolder(config, plugins, lifecycleOwner, LocalViewModelStoreOwner.current)

@Composable
private fun rememberPlatformHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
    viewModelStoreOwner: ViewModelStoreOwner?,
): CameraKStateHolder {
    val context = LocalContext.current
    return rememberLifecycleHolder(config, plugins, lifecycleOwner, viewModelStoreOwner) { c, p, after ->
        CameraKStateHolder(c, CameraKPlatformContext(context), p, after)
    }
}
