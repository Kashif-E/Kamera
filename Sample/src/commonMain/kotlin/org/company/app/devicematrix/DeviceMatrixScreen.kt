package org.company.app.devicematrix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.compose.CameraPreview
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.permissions.rememberCameraKPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** This platform's [MatrixPlatform]; [permission] reads the statuses the screen holds. */
@Composable
internal expect fun rememberMatrixPlatform(permission: (CameraKPermission) -> PermissionStatus?): MatrixPlatform

/** Keeps the display awake while the matrix runs. */
@Composable
internal expect fun KeepScreenOn()

/**
 * The device matrix's screen, shown instead of the Sample when it is launched for the matrix: it
 * asks for the camera, microphone and gallery, runs [MatrixRunner] with [args], and shows the live
 * preview of the holder under test with the progress.
 */
@Composable
internal fun DeviceMatrixApp(args: MatrixArgs) {
    val permissions = rememberCameraKPermissionState(
        CameraKPermission.CAMERA,
        CameraKPermission.MICROPHONE,
        CameraKPermission.GALLERY_ADD,
    )
    LaunchedEffect(permissions) {
        if (!permissions.allGranted) permissions.launchRequest()
    }
    val statuses = rememberUpdatedState(permissions.statuses)
    val platform = rememberMatrixPlatform { statuses.value[it] }
    val runner = remember(platform) { MatrixRunner(platform, args) }
    LaunchedEffect(runner) {
        withContext(Dispatchers.Default) { runner.run() }
    }
    KeepScreenOn()
    val holder by runner.holder.collectAsState()
    val progress by runner.progress.collectAsState()
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.systemBars)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            holder?.let { current ->
                key(current) { CameraPreview(current, Modifier.fillMaxSize(), tapToFocus = false) }
            }
        }
        Column(Modifier.padding(12.dp)) {
            val state = if (progress.finished) "finished" else "running"
            Text("CameraK device matrix: $state, ${progress.done} of ${progress.total}", color = Color.White)
            Text(
                Verdict.entries.joinToString("   ") { "${it.label} ${progress.counts[it] ?: 0}" },
                color = Color.White,
            )
            Text(progress.current, color = Color.LightGray, maxLines = 2)
        }
    }
}
