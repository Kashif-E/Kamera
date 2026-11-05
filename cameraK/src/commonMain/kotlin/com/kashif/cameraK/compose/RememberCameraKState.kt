package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.flow.combine

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
 *         config = CameraConfiguration {
 *             cameraLens = CameraLens.BACK
 *             flashMode = FlashMode.AUTO
 *         },
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
fun rememberCameraKState(
    config: CameraConfiguration = CameraConfiguration(),
    setupPlugins: suspend (CameraKStateHolder) -> Unit = {},
): State<CameraKState> {
    // As in 1.x, the holder is remembered in this composition and closes with it, so setupPlugins runs once per holder.
    val holder = rememberPlatformHolder(config, emptyList(), LocalLifecycleOwner.current, viewModelStoreOwner = null)
    LaunchedEffect(holder) { setupPlugins(holder) }
    return holder.collectCameraState()
}

/**
 * Creates and remembers a [CameraKStateHolder] over this platform's camera, created with [plugins].
 *
 * The holder lives in a ViewModel of the nearest `ViewModelStoreOwner`, one per call site, so an
 * Activity recreation (a rotation) keeps it, with its plugins and any recording in progress. It
 * closes when that owner is cleared, for instance when the user leaves the screen; desktop windows and
 * iOS view controllers clear theirs when they close. Without an owner it is remembered and closes
 * when it leaves the composition. A bare `ImageComposeScene` has one owner for the whole process,
 * never cleared, so a test that composes this function there provides its own
 * `LocalViewModelStoreOwner` and clears it.
 *
 * A new [config] closes the holder and builds a new one, whose camera opens once the old one has
 * closed. A change to [plugins], including the new instances a recreated screen remembers, detaches
 * the plugins no longer listed and attaches the new ones; never attach plugins from an effect keyed
 * on the holder.
 *
 * The user's lens, flash and torch are saved with rememberSaveable, so after process death the new
 * holder starts with them; the zoom is saved too, and restored once the configuration can carry it.
 *
 * When [lifecycleOwner] is not null, the holder starts when it reaches STARTED and stops when it
 * drops below, and on every resume it re-checks the camera permission, so a permission granted in
 * the system dialog opens the camera with no retry. A rotation's stop and start fall inside the
 * holder's two-second grace, so a recording carries on. With a null owner the caller starts and
 * stops the holder.
 */
@Composable
fun rememberCameraKStateHolder(
    config: CameraConfiguration = CameraConfiguration(),
    plugins: List<CameraKPlugin> = emptyList(),
    lifecycleOwner: LifecycleOwner? = LocalLifecycleOwner.current,
): CameraKStateHolder = rememberPlatformHolder(config, plugins, lifecycleOwner, LocalViewModelStoreOwner.current)

/** This platform's context for the production holder constructor. */
@Composable
internal expect fun platformContext(): CameraKPlatformContext

/** [CameraKStateHolder.cameraState] as Compose state: lifecycle-aware on Android and iOS, as in 1.x. */
@Composable
internal expect fun CameraKStateHolder.collectCameraState(): State<CameraKState>

// The production holder over this platform's camera.
@OptIn(InternalCameraKApi::class)
@Composable
private fun rememberPlatformHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
    viewModelStoreOwner: ViewModelStoreOwner?,
): CameraKStateHolder {
    val context = platformContext()
    return rememberLifecycleHolder(config, plugins, lifecycleOwner, viewModelStoreOwner) { c, p, after ->
        CameraKStateHolder(c, context, p, after)
    }
}

/** The default Compose path over an engine factory and an environment: this module's tests use it with a fake engine. */
@OptIn(InternalCameraKApi::class)
@CameraKEngineApi
@Composable
internal fun rememberCameraKStateHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
    engineFactory: CameraEngineFactory,
    environment: CameraKEnvironment,
    viewModelStoreOwner: ViewModelStoreOwner? = LocalViewModelStoreOwner.current,
): CameraKStateHolder = rememberLifecycleHolder(config, plugins, lifecycleOwner, viewModelStoreOwner) { c, p, after ->
    CameraKStateHolder(c, engineFactory, p, environment, after)
}

/**
 * Keeps the holder [factory] builds for [config]: in a [CameraKHolderViewModel] of
 * [viewModelStoreOwner], or, without one, remembered and closed on dispose. Applies [plugins]
 * changes and drives the holder from [lifecycleOwner].
 */
@Composable
internal fun rememberLifecycleHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    lifecycleOwner: LifecycleOwner?,
    viewModelStoreOwner: ViewModelStoreOwner?,
    factory: HolderFactory,
): CameraKStateHolder {
    // The choices saved before process death: what this composition was restored with, if anything.
    val userState = rememberSaveable(saver = CameraUserStateBox.Saver) { CameraUserStateBox(null) }
    val restored = remember { userState.value }
    val seed: (CameraConfiguration) -> CameraConfiguration = { it.seededBy(restored) }
    val holder: CameraKStateHolder
    if (viewModelStoreOwner != null) {
        val model = viewModel(viewModelStoreOwner, key = holderKey(currentCompositeKeyHashCode)) {
            CameraKHolderViewModel()
        }
        holder = remember(model, config) { model.holderFor(config, plugins, seed, factory) }
        LaunchedEffect(model, holder, plugins) { model.applyPlugins(plugins) }
    } else {
        holder = rememberDisposedHolder(config, plugins, seed, factory)
    }
    LaunchedEffect(holder) {
        combine(holder.uiState, holder.zoom) { ui, zoom ->
            val lens = ui.cameraLens
            val flash = ui.flashMode
            val torch = ui.torchMode
            // Null until the first bind reports a camera.
            if (lens == null ||
                flash == null ||
                torch == null
            ) {
                null
            } else {
                CameraUserState(lens, flash, torch, zoom.ratio)
            }
        }.collect { reported -> if (reported != null) userState.value = reported }
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

// Without a ViewModelStoreOwner: remembered per config, closed when it leaves the composition.
@OptIn(InternalCameraKApi::class)
@Composable
private fun rememberDisposedHolder(
    config: CameraConfiguration,
    plugins: List<CameraKPlugin>,
    seed: (CameraConfiguration) -> CameraConfiguration,
    factory: HolderFactory,
): CameraKStateHolder {
    val previous = remember { DisposedHolders() }
    val holder = remember(config) {
        val startAfter = previous.last?.closeCompletion
        factory.create(if (previous.last == null) seed(config) else config, plugins, startAfter).also {
            previous.last = it
            previous.applied = plugins
        }
    }
    DisposableEffect(holder) {
        onDispose { holder.close() }
    }
    LaunchedEffect(holder, plugins) { previous.applied = holder.replacePlugins(previous.applied, plugins) }
    return holder
}

// The holder last built in this composition and the plugins last applied to it.
private class DisposedHolders {
    var last: CameraKStateHolder? = null
    var applied: List<CameraKPlugin> = emptyList()
}

private fun holderKey(compositeKey: Long): String = "com.kashif.cameraK.CameraKHolder:$compositeKey"
