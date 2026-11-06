package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kashif.cameraK.controller.DesktopCameraControllerBuilder
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CancellationException

/**
 * Desktop implementation of [rememberCameraKState].
 */
@Composable
actual fun rememberCameraKState(
    config: CameraConfiguration,
    setupPlugins: suspend (CameraKStateHolder) -> Unit,
): State<CameraKState> {
    val scope = rememberCoroutineScope()

    val stateHolder =
        remember(config) {
            CameraKStateHolder(
                cameraConfiguration = config,
                controllerFactory = {
                    DesktopCameraControllerBuilder()
                        .apply {
                            setImageFormat(config.imageFormat)
                            setDirectory(config.directory)
                            setAspectRatio(config.aspectRatio)
                            setMirrorFrontCamera(config.mirrorFrontCamera)
                            config.targetResolution?.let { (width, height) ->
                                setResolution(width, height)
                            }
                        }.build()
                },
                coroutineScope = scope,
            )
        }

    // Initialize controller and plugins
    LaunchedEffect(stateHolder) {
        setupPlugins(stateHolder)
        try {
            stateHolder.initialize()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Already published as CameraKState.Error; don't crash the host app.
        }
    }

    // Cleanup on disposal
    DisposableEffect(stateHolder) {
        onDispose {
            stateHolder.shutdown()
        }
    }

    return stateHolder.cameraState.collectAsState()
}
