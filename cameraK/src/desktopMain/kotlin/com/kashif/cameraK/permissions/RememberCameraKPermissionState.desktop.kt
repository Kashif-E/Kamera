package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

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
            // Prompts for nothing; core re-reads the statuses and delivers them on the event thread.
            requestPlatformPermissions(platformContext, requested) { statuses ->
                state.publish(statuses)
                currentOnResult(statuses)
            }
        }
    }
    RefreshOnResume(state)
    return state
}
