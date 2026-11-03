package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.LifecycleOwner
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * Creates and remembers a [CameraKStateHolder] with automatic lifecycle management.
 * This is the primary entry point for using CameraK in Compose applications.
 *
 * **Lifecycle:** Automatically initialized on first composition and cleaned up on disposal.
 * **Thread-safe:** Safe to use across multiple composables.
 *
 * @param config Camera configuration settings.
 * @param setupPlugins Lambda to configure plugins before initialization.
 * @return State containing the current [CameraKState].
 *
 * @example
 * ```kotlin
 * @Composable
 * fun CameraScreen() {
 *     val cameraState by rememberCameraKState(
 *         config = CameraConfiguration(
 *             cameraLens = CameraLens.BACK,
 *             flashMode = FlashMode.AUTO
 *         )
 *     )
 *
 *     when (cameraState) {
 *         CameraKState.Initializing -> LoadingIndicator()
 *         is CameraKState.Ready -> CameraPreviewView(cameraState.controller)
 *         is CameraKState.Error -> ErrorScreen(cameraState.message)
 *     }
 * }
 * ```
 */
@Composable
expect fun rememberCameraKState(
    config: CameraConfiguration = CameraConfiguration(),
    setupPlugins: suspend (CameraKStateHolder) -> Unit = {},
): State<CameraKState>

/** H1' red stub: the default Compose path over an engine factory, for this module's tests. */
@CameraKEngineApi
@Composable
internal fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
    engineFactory: CameraEngineFactory,
    environment: CameraKEnvironment,
): CameraKStateHolder = TODO("H1' red stub: rememberCameraKStateHolder")
