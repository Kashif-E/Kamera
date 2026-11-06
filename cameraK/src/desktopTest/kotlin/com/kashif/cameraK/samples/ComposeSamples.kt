package com.kashif.cameraK.samples

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.rememberCameraKStateHolder
import com.kashif.cameraK.compose.rememberRecordingElapsed
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.rememberCameraKPermissionState
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.launch

// KDoc samples for camerak, referenced with @sample. They live here so the compiler checks them.

@Composable
internal fun CameraKScreenSample() {
    val holder = rememberCameraKStateHolder()
    val scope = rememberCoroutineScope()
    CameraKScreen(
        holder = holder,
        loading = { CircularProgressIndicator() },
        error = { error, retry ->
            Column {
                Text("The camera failed: ${error::class.simpleName}")
                if (error.isRetryable) Button(onClick = retry) { Text("Retry") }
            }
        },
    ) {
        Button(
            onClick = { scope.launch { holder.capture() } },
            modifier = Modifier.align(Alignment.BottomCenter).keepUpright(),
        ) { Text("Capture") }
    }
}

@Composable
internal fun RememberCameraKPermissionStateSample(holder: CameraKStateHolder) {
    val permissions = rememberCameraKPermissionState(CameraKPermission.CAMERA)
    if (permissions.allGranted) {
        CameraKScreen(holder)
    } else {
        Button(onClick = permissions::launchRequest) { Text("Allow camera") }
    }
}

@Composable
internal fun RememberRecordingElapsedSample(holder: CameraKStateHolder) {
    val elapsed by rememberRecordingElapsed(holder)
    Text("${elapsed.inWholeSeconds} s")
}
