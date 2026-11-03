package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * Creates and remembers a [CameraKStateHolder] and returns its [CameraKState].
 *
 * The holder starts when the host reaches STARTED, stops below it, re-checks the camera permission
 * on every resume, and closes when it leaves the composition. [setupPlugins] runs once per holder;
 * plugins it attaches attach when the camera is first ready.
 *
 * @param config Camera configuration settings; a new value builds a new holder.
 * @param setupPlugins Attaches plugins to the holder.
 * @return State containing the current [CameraKState].
 *
 * @example
 * ```kotlin
 * @Composable
 * fun CameraScreen() {
 *     val cameraState by rememberCameraKState(
 *         config = CameraConfiguration(cameraLens = CameraLens.BACK, flashMode = FlashMode.AUTO),
 *     )
 *
 *     when (val state = cameraState) {
 *         CameraKState.Idle, CameraKState.Starting, is CameraKState.Interrupted -> LoadingIndicator()
 *         is CameraKState.Ready -> CameraPreviewView(state.controller)
 *         is CameraKState.Error -> ErrorScreen(state.error)
 *         CameraKState.Closed -> Unit
 *     }
 * }
 * ```
 */
@Composable
expect fun rememberCameraKState(
    config: CameraConfiguration = CameraConfiguration(),
    setupPlugins: suspend (CameraKStateHolder) -> Unit = {},
): State<CameraKState>

/**
 * Creates and remembers a [CameraKStateHolder] over this platform's camera, created with [plugins].
 *
 * When [lifecycleOwner] is not null, the holder starts when it reaches STARTED and stops when it
 * drops below, and on every resume it re-checks the camera permission, so a permission granted in
 * the system dialog opens the camera with no retry. With a null owner the caller starts and stops
 * the holder. The holder closes when it leaves the composition, and a new [config] builds a new one.
 */
@Composable
expect fun rememberCameraKStateHolder(
    config: CameraConfiguration = CameraConfiguration(),
    plugins: List<CameraKPlugin> = emptyList(),
    lifecycleOwner: LifecycleOwner? = LocalLifecycleOwner.current,
): CameraKStateHolder

/** The default Compose path over an engine factory and an environment: this module's tests use it with a fake engine. */
@CameraKEngineApi
@Composable
internal fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
    engineFactory: CameraEngineFactory,
    environment: CameraKEnvironment,
): CameraKStateHolder = rememberLifecycleHolder(config, lifecycleOwner) {
    CameraKStateHolder(config, engineFactory, plugins, environment)
}

/** Remembers the holder [create] builds for [config], drives it from [lifecycleOwner] and closes it on dispose. */
@Composable
internal fun rememberLifecycleHolder(
    config: CameraConfiguration,
    lifecycleOwner: LifecycleOwner?,
    create: () -> CameraKStateHolder,
): CameraKStateHolder {
    val holder = remember(config) { create() }
    DisposableEffect(holder) {
        onDispose { holder.close() }
    }
    if (lifecycleOwner != null) {
        LifecycleStartEffect(holder, lifecycleOwner = lifecycleOwner) {
            holder.start()
            onStopOrDispose { holder.stop() }
        }
        LifecycleResumeEffect(holder, lifecycleOwner = lifecycleOwner) {
            holder.recheckPermission()
            onPauseOrDispose { }
        }
    }
    return holder
}
