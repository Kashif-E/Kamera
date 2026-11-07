package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder

/**
 * The camera screen for [holder]: one slot per [CameraKState].
 *
 * - Idle and Starting show [loading].
 * - Ready and Interrupted show the camera preview with [content] over it, in the box the frame is
 *   drawn in. The preview and [content] stay composed from one to the other, so a Stop button
 *   stays reachable while a phone call holds the camera.
 * - Error shows [error], with the error and a `retry` that calls [CameraKStateHolder.retry].
 * - Closed shows nothing.
 *
 * The defaults draw no text or colours of the library's own. The default [error] slot retries a
 * retryable error on its own, once until the camera is Ready again. It leaves `Error(Permission)`
 * alone: [rememberCameraKStateHolder] re-checks the permission when the host resumes. The preview is
 * always drawn; to show none, read [holder] without this screen.
 *
 * @param holder The camera, usually from [rememberCameraKStateHolder].
 * @param modifier Modifier for the screen's box.
 * @param tapToFocus Whether a tap on the preview focuses there; see [CameraPreview].
 * @param focusIndicator Drawn for the latest focus tap; see [CameraPreview].
 * @param loading Shown while the camera is idle or starting.
 * @param error Shown when the camera failed, with the error and a retry.
 * @param content Drawn over the preview, with [CameraPreviewScope] for orientation-aware overlays.
 *
 * @sample com.kashif.cameraK.samples.CameraKScreenSample
 */
@Composable
public fun CameraKScreen(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    tapToFocus: Boolean = true,
    focusIndicator: @Composable (FocusTap) -> Unit = { DefaultFocusIndicator(it) },
    loading: @Composable () -> Unit = {},
    error: @Composable (error: CameraKError, retry: () -> Unit) -> Unit = DefaultCameraKError,
    content: @Composable CameraPreviewScope.() -> Unit = {},
) {
    val state by holder.cameraState.collectAsState()
    val retry: () -> Unit = remember(holder) { { holder.retry() } }
    val autoRetry = remember(holder) { AutoRetry(holder) }
    LaunchedEffect(autoRetry) {
        holder.cameraState.collect { if (it is CameraKState.Ready) autoRetry.rearm() }
    }

    Box(modifier = modifier) {
        when (val current = state) {
            CameraKState.Idle, CameraKState.Starting -> loading()
            is CameraKState.Ready, is CameraKState.Interrupted ->
                CameraPreview(holder, Modifier.fillMaxSize(), tapToFocus = tapToFocus, overlay = content)
            is CameraKState.Error ->
                if (error === DefaultCameraKError) {
                    DefaultErrorSlot(current.error, autoRetry::retryOnce)
                } else {
                    error(current.error, retry)
                }
            CameraKState.Closed -> Unit
        }
    }
}

/**
 * The default error slot's one retry. A retry that fails again shows the error again, and only a
 * camera that reached Ready since earns another, so an error that keeps coming back is not retried
 * in a loop. Used on the composition's thread only.
 */
private class AutoRetry(private val holder: CameraKStateHolder) {
    private var spent = false

    fun retryOnce() {
        if (spent) return
        spent = true
        holder.retry()
    }

    fun rearm() {
        spent = false
    }
}

internal val DefaultCameraKError: @Composable (CameraKError, () -> Unit) -> Unit = { error, retry ->
    DefaultErrorSlot(error, retry)
}

/** Draws nothing. Retries a retryable error, except `Permission`, which the resume re-check handles. */
@Composable
internal fun DefaultErrorSlot(error: CameraKError, retry: () -> Unit) {
    LaunchedEffect(error) {
        if (error.isRetryable && error !is CameraKError.Permission) retry()
    }
}

/** Removed in 2.0. */
@Deprecated(
    "Removed in CameraK 2.0: CameraKScreen takes the holder and draws the preview itself.",
    ReplaceWith("CameraKScreen(holder, modifier)"),
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
@Composable
public fun CameraKScreen(
    modifier: Modifier = Modifier,
    cameraState: CameraKState,
    deviceOrientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    loadingContent: @Composable () -> Unit = {},
    errorContent: @Composable (CameraKState.Error) -> Unit = {},
    showPreview: Boolean = true,
    overlay: @Composable (CameraPreviewScope.() -> Unit)? = null,
    content: @Composable (CameraKState.Ready) -> Unit,
): Unit = throw UnsupportedOperationException(REMOVED_IN_2)

/** What a 1.x hint throws when code compiled against 1.x reaches it. */
internal const val REMOVED_IN_2 = "Removed in CameraK 2.0; see the migration guide"
