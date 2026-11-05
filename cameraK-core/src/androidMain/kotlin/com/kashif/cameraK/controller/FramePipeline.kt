package com.kashif.cameraK.controller

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The frame consumers (plugins' analyzers) and the one [ImageAnalysis] that feeds them. The bound
 * ImageAnalysis feeds them through one [MultiplexingAnalyzer] that reads the consumer list on every
 * frame, so registering or removing one never touches CameraX bindings.
 */
internal class FramePipeline(private val backend: CameraXBackend, private val analyzerExecutor: Executor) {
    private val consumers = atomic(emptyList<ImageAnalysis.Analyzer>())
    private val multiplexer = MultiplexingAnalyzer { consumers.value }

    // Set by the configuration's frameAnalysis or by the first registration, and kept for the
    // engine's lifetime: from then on every bind includes one ImageAnalysis, so later consumers join
    // without a rebind.
    @Volatile
    var analysisRequested = false
        private set

    /** Asks every bind from now on for an ImageAnalysis, before anyone consumes frames (Decision 14). */
    fun requestAnalysis() {
        analysisRequested = true
    }

    /** The ImageAnalysis use case of the current bind, or null when none was built. */
    var analysis: ImageAnalysis? = null

    // Whether the multiplexer is set on the current analysis. Main thread only.
    private var multiplexerAttached = false

    /** Adds [analyzer] as a frame consumer and asks every later bind for an ImageAnalysis. Any thread. */
    fun add(analyzer: ImageAnalysis.Analyzer) {
        consumers.update { it + analyzer }
        analysisRequested = true
    }

    /** Removes [analyzer]; a frame already being dispatched may still reach it. Any thread. */
    fun remove(analyzer: ImageAnalysis.Analyzer) {
        consumers.update { it - analyzer }
    }

    /**
     * Builds the [ImageAnalysis] use case, baking in [pinnedPhysicalId] via Camera2Interop, makes it
     * the current [analysis] and attaches the multiplexer when someone is consuming. Construction
     * only: it does not bind.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun build(pinnedPhysicalId: String?): ImageAnalysis {
        val built = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .build()
        analysis = built
        multiplexerAttached = false
        applyConsumers()
        return built
    }

    /** Attaches the multiplexer while anyone consumes frames and detaches it when no one does. */
    fun applyConsumers() {
        val current = analysis ?: return
        val wanted = consumers.value.isNotEmpty()
        if (wanted == multiplexerAttached) return
        // Run analysis off the main thread — frame work (e.g. JPEG-encoding the analyzer frame,
        // QR/OCR) is too heavy to block the UI thread.
        if (wanted) {
            backend.setAnalyzer(current, analyzerExecutor, multiplexer)
        } else {
            backend.clearAnalyzer(current)
        }
        multiplexerAttached = wanted
    }

    /** Stops frame delivery, before anything the consumers use goes away. */
    fun stopDelivery() {
        analysis?.let { backend.clearAnalyzer(it) }
    }

    /** Drops every consumer. */
    fun clearConsumers() {
        consumers.value = emptyList()
    }
}

/**
 * One 1.x consumer's share of an [ImageProxy] (the facade's `registerImageAnalyzer`, until C1'). The
 * real proxy is closed only when the last share is closed; each share's close counts once.
 *
 * Each holder reads the planes through its own read-only views of CameraX's buffers, so one analyzer
 * reading or trying to write a plane cannot move the position or change the pixels another analyzer
 * sees. [getImage] is still CameraX's shared image: read it, never write to it.
 */
internal class LegacyImageProxy(private val delegate: ImageProxy, private val refCount: AtomicInteger) :
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
internal class ReadOnlyPlane(plane: ImageProxy.PlaneProxy) : ImageProxy.PlaneProxy by plane {
    private val buffer: ByteBuffer = plane.buffer.let { it.asReadOnlyBuffer().order(it.order()) }

    override fun getBuffer(): ByteBuffer = buffer
}
