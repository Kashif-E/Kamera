package com.kashif.cameraK.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder

/**
 * CompositionLocal for providing [CameraKStateHolder] to descendants.
 * Use this to avoid passing the state holder through multiple levels of composables.
 *
 * @example
 * ```kotlin
 * CompositionLocalProvider(LocalCameraKStateHolder provides stateHolder) {
 *     // Descendants can access stateHolder via LocalCameraKStateHolder.current
 *     CameraControls()
 * }
 * ```
 */
val LocalCameraKStateHolder = compositionLocalOf<CameraKStateHolder?> { null }

@Composable
fun CameraKScreen(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = {},
    error: @Composable (error: CameraKError, retry: () -> Unit) -> Unit = DefaultCameraKError,
    content: @Composable CameraPreviewScope.() -> Unit = {},
) {
    TODO("UI'")
}

internal val DefaultCameraKError: @Composable (CameraKError, () -> Unit) -> Unit = { error, retry ->
    DefaultErrorSlot(error, retry)
}

@Composable
internal fun DefaultErrorSlot(error: CameraKError, retry: () -> Unit) {
    TODO("UI'")
}

/**
 * Root camera screen that provides state to all descendants.
 * This composable manages the full camera lifecycle and provides a slot-based API.
 *
 * @param modifier Modifier for the root container.
 * @param cameraState The current camera state from [rememberCameraKState].
 * @param loadingContent Composable to show while the camera is idle, starting or interrupted (default: loading indicator).
 * @param errorContent Composable to show on error (default: error message).
 * @param showPreview Whether to automatically show the camera preview (default: true).
 * @param content Main content to display when camera is ready. Receives the Ready state.
 *
 * @example
 * ```kotlin
 * @Composable
 * fun MyCameraApp() {
 *     val cameraState by rememberCameraKState()
 *
 *     CameraKScreen(
 *         cameraState = cameraState,
 *         loadingContent = { CustomLoadingSpinner() },
 *         errorContent = { error -> CustomErrorUI(error) }
 *     ) { state ->
 *         // Camera preview is shown automatically
 *         // Add controls overlay here
 *         Box(modifier = Modifier.fillMaxSize()) {
 *             CameraControls(
 *                 uiState = holder.uiState.collectAsState().value,
 *                 modifier = Modifier.align(Alignment.BottomCenter)
 *             )
 *         }
 *     }
 * }
 * ```
 */
@Composable
fun CameraKScreen(
    modifier: Modifier = Modifier,
    cameraState: CameraKState,
    deviceOrientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    loadingContent: @Composable () -> Unit = { DefaultLoadingScreen() },
    errorContent: @Composable (CameraKState.Error) -> Unit = { DefaultErrorScreen(it) },
    showPreview: Boolean = true,
    overlay: @Composable (CameraPreviewScope.() -> Unit)? = null,
    content: @Composable (CameraKState.Ready) -> Unit,
) {
    Box(modifier = modifier) {
        when (cameraState) {
            CameraKState.Idle, CameraKState.Starting, is CameraKState.Interrupted -> loadingContent()
            is CameraKState.Ready -> {
                if (showPreview) {
                    @Suppress("DEPRECATION")
                    CameraPreviewView(
                        controller = cameraState.controller,
                        modifier = Modifier.fillMaxSize(),
                        deviceOrientation = deviceOrientation,
                        overlay = overlay,
                    )
                }
                content(cameraState)
            }
            is CameraKState.Error -> errorContent(cameraState)
            CameraKState.Closed -> Unit
        }
    }
}

/**
 * Default loading screen shown during camera initialization.
 */
@Composable
fun DefaultLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = "Initializing Camera...",
                color = Color.White,
            )
        }
    }
}

/**
 * Default error screen shown when camera initialization fails: a fixed text for the kind of
 * error, never one built from a platform message.
 *
 * @param errorState The error state.
 */
@Composable
fun DefaultErrorScreen(errorState: CameraKState.Error) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "Camera Error",
                color = Color.Red,
            )
            Text(
                text = errorState.error.defaultText(),
                color = Color.White,
            )
            if (errorState.isRetryable) {
                Text(
                    text = "Please try again",
                    color = Color.Gray,
                )
            }
        }
    }
}

// A fixed text per kind of error. CameraKError's messages are not API and a cause's message may hold a path.
internal fun CameraKError.defaultText(): String = when (this) {
    is CameraKError.Permission -> if (needsSettings) "Allow camera access in Settings" else "Camera access is needed"
    is CameraKError.CameraInUse -> "The camera is in use by another app"
    is CameraKError.CameraDisabled -> "The camera is turned off on this device"
    is CameraKError.NoCamera -> "No camera was found"
    is CameraKError.Disconnected -> "The camera was disconnected"
    is CameraKError.Storage -> "The photo or video could not be saved"
    is CameraKError.Unsupported -> "This camera setting is not supported"
    is CameraKError.NativeLibraryMissing -> "A camera library is missing"
    is CameraKError.Hardware -> "The camera reported a hardware error"
    is CameraKError.Timeout -> "The camera took too long to respond"
    is CameraKError.Busy -> "The camera is busy"
    is CameraKError.InvalidState -> "The camera cannot do that now"
    CameraKError.NotReady -> "The camera is not ready"
    CameraKError.Closed -> "The camera is closed"
    is CameraKError.Internal -> "Something went wrong with the camera"
}
