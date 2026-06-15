package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.image.DataBufferByte

actual fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit) {
    val scope = CoroutineScope(Dispatchers.Default)

    scope.launch {
        cameraController.frameFlow.collect { image ->
            onFrameAvailable((image.raster.dataBuffer as DataBufferByte).data)
        }
    }
}
