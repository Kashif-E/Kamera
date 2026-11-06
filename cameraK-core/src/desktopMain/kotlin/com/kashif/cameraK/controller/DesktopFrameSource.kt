package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.WorkerDispatcher
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.AnalyzerGate
import com.kashif.cameraK.frames.AnalyzerLease
import com.kashif.cameraK.frames.AnalyzerRetain
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.RetainedFrame
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext

/** How a leased frame becomes the analysis planes of [format] at [size]; [LeasedFrame.analysisPlanes] unless a test fails it. */
internal fun interface AnalysisConversion {
    fun planes(frame: LeasedFrame, format: FrameFormat, size: Resolution?): List<FramePlane>
}

/** The production conversion: [LeasedFrame.analysisPlanes]. */
internal val LeasedFrameConversion = AnalysisConversion { frame, format, size -> frame.analysisPlanes(format, size) }

/**
 * The desktop frame source (§8 desktop). The single producer hands it each leased frame of the
 * open camera ([deliver], on the producer thread). Every registration has its own [AnalyzerGate]
 * and, unless synchronous, its own worker thread from the runtime: a busy analyzer misses frames
 * and never queues them, so each worker is conflated to the latest frame. A frame reaching an
 * analyzer holds a lease on its slot until the analyzer returns and every retain is closed.
 *
 * Analysis frames are in the session's [format] and at its [analysisSize] (the blitted frame's
 * size when null), converted from the slot only when an analyzer takes the frame, once per frame.
 * A synchronous registration runs on the producer thread and reads that same converted frame:
 * desktop has no platform buffer to hand it. A registration whose `acceptedFormats` lacks [format] is
 * rejected with `Unsupported(ANALYSIS_FORMAT)` and never active. Every other registration is active
 * from the start, because a desktop session always has its frames: there is no output to add.
 *
 * The producer's pool is sized from the registrations ([poolSize], W7b): a registration counts from
 * its registration until it is closed and every frame it was handed has come back, so an analyzer
 * at its `maxRetained` is skipped alone and the preview, stills, the recording and every other
 * analyzer keep getting frames.
 */
internal class DesktopFrameSource(
    private val format: FrameFormat,
    private val analysisSize: Resolution?,
    private val newWorker: (String) -> WorkerDispatcher,
    private val nanoTime: () -> Long,
    /** Converts a slot to the analysis planes; tests pass one that fails. */
    private val convert: AnalysisConversion = LeasedFrameConversion,
) : FrameSource {
    private val registrations = CopyOnWriteArrayList<Registration>()

    // The registrations the pool is sized for: the open ones and the closed ones still holding frames.
    private val pooled = CopyOnWriteArrayList<Registration>()
    private val poolLock = Any()

    @Volatile private var closed = false

    /** The slots the producer's pool may hold for the registrations now counted ([framePoolSize]). */
    @Volatile var poolSize: Int = framePoolSize(retainLimits = 0, analyzers = 0)
        private set

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration {
        if (format !in spec.acceptedFormats) {
            CameraKLogger.w(TAG, "A frame analyzer cannot read the session's $format frames; it is rejected")
            return Registration(spec, analyzer, CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT), worker = null)
        }
        val gate = AnalyzerGate(spec.maxFps, nanoTime, spec.maxRetained)
        // After the engine closed no frame comes: the registration is inactive from the start.
        if (closed) return Registration(spec, analyzer, null, worker = null, gate = gate).also { it.close() }
        val worker = if (spec.synchronous) null else newWorker(ANALYZER_THREAD)
        return Registration(spec, analyzer, null, worker, gate).also {
            // Sized before the first frame can reach it.
            pooled += it
            resizePool()
            registrations += it
            if (closed) it.close()
        }
    }

    private fun resizePool() = synchronized(poolLock) {
        poolSize = framePoolSize(retainLimits = pooled.sumOf { it.retainLimit }, analyzers = pooled.size)
    }

    private fun leavePool(registration: Registration) {
        if (pooled.remove(registration)) resizePool()
    }

    /** Hands [frame] to every idle registration. Producer thread only, while it holds a lease on [frame]. */
    fun deliver(frame: LeasedFrame) {
        if (registrations.isEmpty()) return
        var planes: List<FramePlane>? = null
        for (registration in registrations) {
            val gate = registration.gate ?: continue
            val lease = gate.tryEnter(onReleased = { registration.returned(frame) }) ?: continue
            registration.handed()
            frame.retain()
            val view = planes ?: try {
                convert.planes(frame, format, analysisSize).also { planes = it }
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "A camera frame could not be converted for analysis; the frame is skipped", e)
                lease.release()
                return
            }
            registration.dispatch(DesktopCameraFrame(frame, format, view, lease), lease)
        }
    }

    /** The engine closed: no frame comes again, and every registration's worker ends. */
    fun close() {
        closed = true
        registrations.forEach { it.close() }
    }

    private inner class Registration(
        private val spec: FrameSpec,
        private val analyzer: FrameAnalyzer,
        override val rejection: CameraKError?,
        private val worker: WorkerDispatcher?,
        val gate: AnalyzerGate? = null,
    ) : FrameRegistration {
        private val _active = MutableStateFlow(rejection == null && gate != null)
        override val active: StateFlow<Boolean> = _active.asStateFlow()

        @Volatile private var open = rejection == null

        /** The frames the registration may keep, as its gate counts them. */
        val retainLimit: Int = spec.maxRetained.coerceAtLeast(1)

        // Frames handed to the analyzer and not yet given back: an entry or a retain still holds them.
        private val outstanding = atomic(0)

        fun handed() {
            outstanding.incrementAndGet()
        }

        fun returned(frame: LeasedFrame) {
            frame.release()
            if (outstanding.decrementAndGet() == 0 && !open) leavePool(this)
        }

        fun dispatch(frame: CameraFrame, lease: AnalyzerLease) {
            if (spec.synchronous || worker == null) {
                lease.deliver { analyzer.analyze(frame) }
                return
            }
            try {
                worker.dispatcher.dispatch(EmptyCoroutineContext, Runnable { run(frame, lease) })
            } catch (e: Exception) {
                CameraKLogger.w(TAG, "A frame could not reach its analyzer's worker; the frame is skipped", e)
                lease.release()
            }
        }

        // A frame dispatched before the registration closed is given back, never analyzed.
        private fun run(frame: CameraFrame, lease: AnalyzerLease) {
            if (open) lease.deliver { analyzer.analyze(frame) } else lease.release()
        }

        override fun close() {
            open = false
            _active.value = false
            if (registrations.remove(this)) worker?.close()
            if (outstanding.value == 0) leavePool(this)
        }
    }

    private companion object {
        const val TAG = "CameraK"
        const val ANALYZER_THREAD = "CameraK-analyzer"
    }
}

/**
 * One analysis frame: a borrowed view of a leased slot in the session's format and analysis size.
 * Desktop pixels are upright, so the rotation is 0, and the crop rectangle is the whole frame (the
 * aspect ratio's crop already happened in the blit, and analysisResolution's in the scaling).
 */
internal class DesktopCameraFrame(
    frame: LeasedFrame,
    override val format: FrameFormat,
    override val planes: List<FramePlane>,
    private val lease: AnalyzerLease,
) : CameraFrame {
    override val width: Int = frame.analysisWidth
    override val height: Int = frame.analysisHeight
    override val rotationDegrees: Int get() = 0
    override val timestampNanos: Long = frame.captureNanos
    override val cropRect: FrameRect = FrameRect(0, 0, width, height)

    override fun retain(): RetainedFrame = Retained(lease.retain())

    private inner class Retained(private val handle: AnalyzerRetain) :
        RetainedFrame,
        CameraFrame by this@DesktopCameraFrame {
        override fun close() = handle.release()
    }
}

/**
 * A plane over part of a pooled buffer: [size] bytes from [offset], rows [rowStride] bytes apart.
 * Read-only to its consumers, who copy out of it.
 */
internal class DesktopFramePlane(
    private val bytes: ByteArray,
    private val offset: Int,
    override val rowStride: Int,
    override val pixelStride: Int,
    override val size: Int,
) : FramePlane {
    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
        System.arraycopy(bytes, offset, destination, destinationOffset, size)
        return size
    }

    override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
        require(sourceOffset >= 0 && length >= 0 && sourceOffset <= size - length) {
            "copyTo($sourceOffset, $length) reads outside a plane of $size bytes"
        }
        System.arraycopy(bytes, offset + sourceOffset, destination, destinationOffset, length)
    }
}
