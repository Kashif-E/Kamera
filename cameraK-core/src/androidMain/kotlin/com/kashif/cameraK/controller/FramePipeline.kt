package com.kashif.cameraK.controller

import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import com.kashif.cameraK.engine.WorkerDispatcher
import com.kashif.cameraK.engine.newDedicatedWorker
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.AnalyzerGate
import com.kashif.cameraK.frames.AnalyzerLease
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private const val TAG = "CameraK"

/**
 * The engine's [FrameSource] and the one [ImageAnalysis] that feeds it (design §8). The bound
 * ImageAnalysis (`STRATEGY_KEEP_ONLY_LATEST`, the session's [sessionFormat], [resolutionSelector])
 * feeds every registration ([register]) through one [MultiplexingAnalyzer] that reads the list on
 * every frame, so registering or removing one never touches CameraX bindings. A synchronous
 * registration gets the proxy itself on the analysis thread; the asynchronous ones share one copy of
 * the planes from a pooled slot, each on its own worker from [newWorker], admitted by its own
 * [AnalyzerGate]. The proxy closes before `analyze` returns, whatever any registration holds.
 *
 * The ImageAnalysis is bound per Decision 14: from the first bind when [requestAnalysis] was called,
 * else by the first registration's one late bind, which the engine runs ([onConsumersChanged]).
 */
internal class FramePipeline(
    private val backend: CameraXBackend,
    private val analyzerExecutor: Executor,
    private val sessionFormat: FrameFormat = FrameFormat.YUV_420,
    private val newWorker: (String) -> WorkerDispatcher = ::newDedicatedWorker,
    private val nanoTime: () -> Long = System::nanoTime,
    /** The analysis selector, keeping only the sizes given when they are set; null leaves CameraX's default. */
    private val resolutionSelector: (outputs: List<Size>?) -> ResolutionSelector? = { null },
) : FrameSource {
    private val registrations = atomic(emptyList<Registration>())
    private val pool = FramePool()
    internal val multiplexer = MultiplexingAnalyzer({ registrations.value }, sessionFormat, pool)
    private val workerCount = atomic(0)

    /**
     * Runs when a registration comes or goes, on the registering thread. The engine sets it to bind
     * the ImageAnalysis late, or attach or detach the multiplexer, on the main thread.
     */
    @Volatile
    var onConsumersChanged: () -> Unit = {}

    // Whether the current bind includes the ImageAnalysis: what a registration's `active` shows.
    @Volatile
    private var outputBound = false

    /** Test seam: runs in [register] between its read of whether the output is bound and its publish. */
    @Volatile
    internal var beforePublishingActive: () -> Unit = {}

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration {
        if (sessionFormat !in spec.acceptedFormats) {
            CameraKLogger.w(TAG, "A frame analyzer that cannot read $sessionFormat frames was registered; it gets none")
            return Registration(spec, analyzer, CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT))
        }
        val registration = Registration(spec, analyzer, rejection = null)
        registrations.update { it + registration }
        analysisRequested = true
        resizePool()
        var bound = outputBound
        beforePublishingActive()
        registration.publishActive(bound)
        // A bind that finished on Main after the read published to this registration before the stale
        // value above did: publish again until the value read after a publish is the one published.
        while (outputBound != bound) {
            bound = outputBound
            registration.publishActive(bound)
        }
        onConsumersChanged()
        return registration
    }

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

    /** Whether a bind must add the ImageAnalysis someone asked for. */
    val analysisMissing: Boolean get() = analysisRequested && analysis == null

    /** The ImageAnalysis use case of the current bind, or null when none was built. */
    var analysis: ImageAnalysis? = null

    // Whether the multiplexer is set on the current analysis. Main thread only.
    private var multiplexerAttached = false

    /**
     * Builds the [ImageAnalysis] use case in the session's format and size, baking in
     * [pinnedPhysicalId] via Camera2Interop and keeping only the YUV sizes the pinned lens [outputs],
     * makes it the current [analysis] and attaches the multiplexer when someone is consuming.
     * Construction only: it does not bind.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun build(pinnedPhysicalId: String?, outputs: List<Size>? = null): ImageAnalysis {
        val selector = resolutionSelector(outputs)
        val built = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(sessionFormat.toOutputImageFormat())
            .apply { selector?.let { setResolutionSelector(it) } }
            .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .build()
        analysis = built
        multiplexerAttached = false
        applyConsumers()
        return built
    }

    /** A bind finished: [bound] says whether it includes the ImageAnalysis. Main thread. */
    fun onBound(bound: Boolean) {
        outputBound = bound
        registrations.value.forEach { it.publishActive(bound) }
    }

    /** Attaches the multiplexer while anyone consumes frames and detaches it when no one does. Main thread. */
    fun applyConsumers() {
        val current = analysis ?: return
        val wanted = registrations.value.isNotEmpty()
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

    /** Closes every registration; a registration made after it is never active. */
    fun clearConsumers() {
        outputBound = false
        registrations.value.forEach { it.close() }
    }

    // Enough slots for every asynchronous registration's retained frames plus one in flight each.
    private fun resizePool() {
        pool.capacity = registrations.value.filter { !it.synchronous }.sumOf { it.retainLimit + 1 }
    }

    /** One FrameSource registration: its gate, its worker when asynchronous, and its `active` state. */
    private inner class Registration(
        spec: FrameSpec,
        private val analyzer: FrameAnalyzer,
        override val rejection: CameraKError?,
    ) : FrameRegistration,
        FrameConsumer {
        override val synchronous: Boolean = spec.synchronous
        val retainLimit: Int = spec.maxRetained.coerceAtLeast(1)
        private val gate = AnalyzerGate(spec.maxFps, nanoTime, spec.maxRetained)
        private val worker: WorkerDispatcher? =
            if (synchronous || rejection != null) null else newWorker("CameraK-frames-${workerCount.incrementAndGet()}")
        private val executor: Executor? = worker?.dispatcher?.asExecutor()
        private val closed = atomic(false)

        private val _active = MutableStateFlow(false)
        override val active: StateFlow<Boolean> = _active.asStateFlow()

        fun publishActive(outputBound: Boolean) {
            _active.value = outputBound && rejection == null && !closed.value
        }

        override fun deliverSynchronously(proxy: ImageProxy, meta: FrameMeta) {
            if (closed.value) return
            val lease = gate.tryEnter() ?: return
            val frame = ProxyFrame(SynchronousImageProxy(proxy), meta, lease)
            lease.deliver { analyzer.analyze(frame) }
        }

        override fun tryEnter(onReleased: () -> Unit): AnalyzerLease? =
            if (closed.value) null else gate.tryEnter(onReleased)

        override fun dispatch(slot: FrameSlot, lease: AnalyzerLease) {
            val target = executor ?: return lease.release()
            val frame = SlotFrame(slot, lease)
            try {
                target.execute {
                    if (closed.value) lease.release() else lease.deliver { analyzer.analyze(frame) }
                }
            } catch (e: RejectedExecutionException) {
                CameraKLogger.w(TAG, "A frame analyzer's worker refused a frame; the frame is released", e)
                lease.release()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            _active.value = false
            if (rejection != null) return
            registrations.update { it - this }
            resizePool()
            worker?.close()
            onConsumersChanged()
        }
    }
}

private fun FrameFormat.toOutputImageFormat(): Int = when (this) {
    FrameFormat.YUV_420 -> ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
    FrameFormat.RGBA_8888 -> ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
}

/**
 * Reusable plane copies for the asynchronous registrations. A slot taken for a frame goes back once
 * every registration it went to has released it; at most [capacity] slots are kept for reuse.
 */
internal class FramePool {
    private val free = ArrayDeque<FrameSlot>()

    @Volatile
    var capacity: Int = 0

    fun take(): FrameSlot = synchronized(free) { free.removeFirstOrNull() } ?: FrameSlot()

    fun give(slot: FrameSlot) {
        synchronized(free) { if (free.size < capacity) free.addLast(slot) }
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
