package com.kashif.cameraK.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(InternalCameraKApi::class)
@Composable
public actual fun rememberCameraKPermissionState(
    vararg permissions: CameraKPermission,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
): CameraKPermissionState {
    val context = LocalContext.current
    val requested = permissions.toList()
    val platformContext = remember(context) { CameraKPlatformContext(context) }
    val state = remember(requested, platformContext) {
        PlatformPermissionState(
            requested,
            // The status reads the request record, a preferences file: never on Main.
            readContext = Dispatchers.IO,
            // Until that read, only the grant, which reads no file.
            initial = { grantedOrNotDetermined(context, it) },
        ) { platformPermissionStatus(platformContext, it) }
    }
    // The reads resume from Dispatchers.IO; statuses and onResult are published on the main thread.
    val scope = rememberCoroutineScope { Dispatchers.Main }
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val activity = context.findActivity()
        scope.launch {
            // An answer the user gave is recorded even if the composition leaves meanwhile.
            withContext(Dispatchers.IO + NonCancellable) { recordPermissionResults(activity, context, results) }
            currentOnResult(state.refresh())
        }
    }
    SideEffect {
        state.request = {
            // A permission this API level does not need (the gallery from API 29) has nothing to ask.
            val names = requested.mapNotNull(::androidPermissionName).distinct()
            if (names.isEmpty()) {
                scope.launch { currentOnResult(state.refresh()) }
            } else {
                launcher.launch(names.toTypedArray())
            }
        }
    }
    RefreshOnResume(state, scope)
    return state
}

/**
 * Records a request's [results] for core's checker. A denied permission whose rationale [activity]
 * no longer shows was denied for good, unless this was its first request (core's record decides).
 * Without an activity there is no rationale to read, so no denial is taken as permanent.
 */
@OptIn(InternalCameraKApi::class)
internal fun recordPermissionResults(activity: Activity?, context: Context, results: Map<String, Boolean>) {
    val record = PermissionRequestRecord(context)
    for ((name, granted) in results) {
        val rationale = activity?.shouldShowRequestPermissionRationale(name) ?: true
        record.recordResult(name, granted, rationale)
    }
}

@OptIn(InternalCameraKApi::class)
private fun grantedOrNotDetermined(context: Context, permission: CameraKPermission): PermissionStatus {
    val name = androidPermissionName(permission) ?: return PermissionStatus.Granted
    return if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) {
        PermissionStatus.Granted
    } else {
        PermissionStatus.NotDetermined
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
