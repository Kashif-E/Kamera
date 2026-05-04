package com.kashif.analyzerPlugin

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.toByteArray

actual fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit) {
    cameraController.enableAnalyzer(onFrameAvailable)
}
internal fun CameraController.enableAnalyzer(onFrameAvailable: (ByteArray) -> Unit): CameraAnalyzer {
    val analyzer = CameraAnalyzer(onFrameAvailable = onFrameAvailable)
    registerImageAnalyzer(analyzer)
    return analyzer
}

internal class CameraAnalyzer(private val onFrameAvailable: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val mediaImage = image.image
            if (mediaImage == null) {
                return
            }
            onFrameAvailable(image.toByteArray())
        } finally {
            image.close()
        }
    }
}
