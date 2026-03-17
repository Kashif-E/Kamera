package com.kashif.cameraK.compose

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation

@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(controller, previewView) {
        controller.bindCamera(previewView) {}
        onDispose {}
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (overlay != null) {
            val scope = CameraPreviewScopeImpl(this, deviceOrientation)
            scope.overlay()
        }
    }
}
