package com.kashif.cameraK.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kashif.cameraK.builder.CameraControllerBuilder
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.DesktopCameraControllerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * @deprecated This is an internal function for the deprecated [CameraPreview] API.
 */
@Composable
actual fun expectCameraPreview(
    modifier: Modifier,
    cameraConfiguration: CameraControllerBuilder.() -> Unit,
    onCameraControllerReady: (CameraController) -> Unit,
) {
    val cameraController =
        remember {
            DesktopCameraControllerBuilder()
                .apply(cameraConfiguration)
                .build()
        }

    BoxWithConstraints(modifier = modifier) {
        val scope = rememberCoroutineScope()

        var img by remember { mutableStateOf<ImageBitmap?>(null) }

        DisposableEffect(Unit) {
            cameraController.startSession()
            cameraController.initializeControllerPlugins()
            onCameraControllerReady(cameraController)

            val frameJob =
                scope.launch(Dispatchers.Main) {
                    cameraController.frameFlow.collect { image ->
                        img = image.toComposeImageBitmap()
                    }
                }

            onDispose {
                frameJob.cancel()
                cameraController.stopSession()
            }
        }

        if (img != null) {
            Image(img!!, contentDescription = null, modifier.fillMaxSize())
        }
    }
}
