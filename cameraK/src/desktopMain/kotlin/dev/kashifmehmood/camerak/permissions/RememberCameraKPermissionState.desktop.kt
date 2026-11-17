package dev.kashifmehmood.camerak.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(InternalCameraKApi::class)
@Composable
public actual fun rememberCameraKPermissionState(
    vararg permissions: CameraKPermission,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
): CameraKPermissionState {
    val requested = permissions.toList()
    val platformContext = remember { CameraKPlatformContext() }
    val state = remember(requested) {
        PlatformPermissionState(requested) { platformPermissionStatus(platformContext, it) }
    }
    val currentOnResult by rememberUpdatedState(onResult)
    SideEffect {
        state.request = {
            // Core asks macOS for what is still undecided and delivers the statuses on the event thread.
            requestPlatformPermissions(platformContext, requested) { statuses ->
                state.publish(statuses)
                currentOnResult(statuses)
            }
        }
    }
    RefreshOnResume(state)
    RefreshWhilePromptPending(state) { requested.any { platformPermissionPromptPending(platformContext, it) } }
    return state
}

/** How often the statuses are read again while a macOS prompt may be up. */
internal val PROMPT_POLL_INTERVAL = 500.milliseconds

/**
 * Reads [state] again every [PROMPT_POLL_INTERVAL] while [pending] holds. macOS's prompt shows when
 * the camera or microphone opens and its answer is no lifecycle event of the app's window, so a
 * resume may never come to read it.
 */
@Composable
internal fun RefreshWhilePromptPending(state: PlatformPermissionState, pending: () -> Boolean) {
    val currentPending by rememberUpdatedState(pending)
    LaunchedEffect(state) {
        while (currentPending()) {
            delay(PROMPT_POLL_INTERVAL)
            state.refresh()
        }
    }
}
