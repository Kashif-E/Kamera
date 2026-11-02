package com.kashif.cameraK.controller

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kashif.cameraK.utils.CameraKLogger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dispatches each camera frame to the [ImageAnalysis.Analyzer] instances [consumers] returns for it.
 * Uses [RefCountedImageProxy] so each analyzer can call `close()` independently;
 * the underlying buffer is released only when all analyzers have finished.
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
            val frame = RefCountedImageProxy(imageProxy, refCount)
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

/**
 * Wraps an [ImageProxy] with reference-counted close semantics.
 * The real proxy is closed only when the last holder calls [close]; each holder's close counts once.
 *
 * Each holder reads the planes through its own read-only views of CameraX's buffers, so one analyzer
 * reading or trying to write a plane cannot move the position or change the pixels another analyzer
 * sees. [getImage] is still CameraX's shared image: read it, never write to it.
 */
private class RefCountedImageProxy(private val delegate: ImageProxy, private val refCount: AtomicInteger) :
    ImageProxy by delegate {
    private val closed = AtomicBoolean(false)

    private val ownPlanes: Array<ImageProxy.PlaneProxy> by lazy {
        delegate.planes.map { plane -> ReadOnlyPlane(plane) as ImageProxy.PlaneProxy }.toTypedArray()
    }

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = ownPlanes

    override fun close() {
        if (closed.compareAndSet(false, true) && refCount.decrementAndGet() == 0) {
            delegate.close()
        }
    }
}

/**
 * A read-only view of [plane]'s buffer, with its own position and limit. asReadOnlyBuffer() always
 * returns a big-endian view, while ImageReader hands out planes in native (little-endian) order, so
 * the source's order is set again for multi-byte reads.
 */
private class ReadOnlyPlane(plane: ImageProxy.PlaneProxy) : ImageProxy.PlaneProxy by plane {
    private val buffer: ByteBuffer = plane.buffer.let { it.asReadOnlyBuffer().order(it.order()) }

    override fun getBuffer(): ByteBuffer = buffer
}
