package com.kashif.cameraK.controller

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kashif.cameraK.utils.CameraKLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dispatches each camera frame to the [ImageAnalysis.Analyzer] instances [consumers] returns for it.
 * Each gets its own [LegacyImageProxy], so each analyzer can call `close()` independently; the
 * underlying buffer is released only when all analyzers have finished.
 */
internal class MultiplexingAnalyzer(private val consumers: () -> List<ImageAnalysis.Analyzer>) :
    ImageAnalysis.Analyzer {
    constructor(analyzers: List<ImageAnalysis.Analyzer>) : this({ analyzers })

    override fun analyze(imageProxy: ImageProxy) {
        val analyzers = consumers()
        if (analyzers.isEmpty()) {
            imageProxy.close()
            return
        }
        val refCount = AtomicInteger(analyzers.size)
        for (analyzer in analyzers) {
            val frame = LegacyImageProxy(imageProxy, refCount)
            // Runs on the analyzer executor, where an escaped exception kills the host process. A
            // failing consumer gives up its share of the frame so the others, and the stream, go on.
            try {
                analyzer.analyze(frame)
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Image analyzer failed", e)
                frame.close()
            }
        }
    }
}
