package com.kashif.cameraK.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    Box(modifier = modifier) {
        BoxWithConstraints(modifier = modifier) {
            val scope = rememberCoroutineScope()
            var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }

            LaunchedEffect(controller) {
                scope.launch(Dispatchers.Default) {
                    controller.frameFlow.collect { frame ->
                        currentFrame = frame.toComposeImageBitmap()
                    }
                }
            }

            currentFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = "Camera Preview",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (overlay != null) {
            val scope = CameraPreviewScopeImpl(this, deviceOrientation)
            scope.overlay()
        }
    }
}
