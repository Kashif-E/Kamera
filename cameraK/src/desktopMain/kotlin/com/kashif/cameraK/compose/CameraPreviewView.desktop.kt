package com.kashif.cameraK.compose

import androidx.compose.foundation.Image
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Desktop implementation of stateless camera preview.
 * Displays frames from JavaCV frame grabber.
 */
@Composable
actual fun CameraPreviewView(controller: CameraController, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val scope = rememberCoroutineScope()
        var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(controller) {
            scope.launch(Dispatchers.Default) {
                controller.frameFlow.collect { frame ->
                    currentFrame = frame.toComposeImageBitmap()
                    println("Consumed frame fom CameraPreviewView, thread ${Thread.currentThread().name}")
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
}
