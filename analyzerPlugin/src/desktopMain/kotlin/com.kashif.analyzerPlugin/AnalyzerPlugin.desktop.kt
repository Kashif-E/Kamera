package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.image.DataBufferByte

actual fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit): AnalyzerHandle {
    val scope = CoroutineScope(Dispatchers.Default)

    scope.launch {
        cameraController.frameFlow.collect { image ->
            onFrameAvailable((image.raster.dataBuffer as DataBufferByte).data)
        }
    }

    // Without this the collector ran forever after detach (stopAnalyzer/onDetach had no effect).
    return AnalyzerHandle { scope.cancel() }
}
