@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.controller.CustomCameraController
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureInputPort
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import kotlin.time.TimeSource

/** Locks and unlocks a pixel buffer's base address; tests count the calls. */
internal interface PixelBufferLocker {
    /** Locks [buffer] read-only; false when Core Video refused. */
    fun lock(buffer: CVPixelBufferRef): Boolean

    fun unlock(buffer: CVPixelBufferRef)
}

internal object CoreVideoPixelBufferLocker : PixelBufferLocker {
    override fun lock(buffer: CVPixelBufferRef): Boolean =
        CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) == 0

    override fun unlock(buffer: CVPixelBufferRef) {
        CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    }
}

/** An iOS registration: what it counts beyond FrameRegistration. */
internal interface AppleFrameRegistration {
    /** Sample buffers AVFoundation dropped while this registration was attached. */
    val droppedFrames: Int
}

/** An iOS retained frame: whether it holds the sample buffer itself or a copy of its luma. */
internal interface AppleRetainedFrame {
    val holdsSampleBuffer: Boolean
}

/**
 * The iOS frame source (design §8): one AVCaptureVideoDataOutput in 420f that discards late frames,
 * on its own serial queue, fanned out to every registration.
 *
 * - **Topology.** The output joins the startup transaction when [keepOutput] (`frameAnalysis`) is
 *   set; otherwise the first registration adds it in a configuration transaction of its own, and the
 *   last one to close removes it. While [recordingActive] the session's outputs are frozen: a
 *   registration attaches only to an output already present and otherwise waits with `active` false,
 *   and nothing is removed. The engine calls [reconcileOnQueue] when a recording has finalized.
 * - **Delivery.** A synchronous registration runs on the output's queue; every other one on its own
 *   serial queue, inside an autorelease pool. Each delivery holds the sample buffer and locks the
 *   pixel buffer's base address for the length of analyze(), and releases both in one finally.
 *   Admission (busy, maxFps, maxRetained, failures) is each registration's [AnalyzerGate].
 * - **Retains.** A retained frame keeps the sample buffer and its own base-address lock until closed,
 *   at most one per registration and [MAX_HELD_BUFFERS] overall; past that, retain() copies the luma.
 * - **Drops.** AVFoundation's didDropSampleBuffer is counted on every attached registration.
 *
 * [onSampleBuffer] hears the input of every buffer delivered, on the output's queue, before any
 * analyzer: the engine's first-frame and lens-switch intervals end there.
 */
internal class AppleFrameSource(
    private val custom: CustomCameraController,
    private val keepOutput: Boolean,
    private val recordingActive: () -> Boolean,
    private val rotationDegrees: () -> Int,
    private val onSampleBuffer: (AVCaptureInput?) -> Unit,
    private val locker: PixelBufferLocker = CoreVideoPixelBufferLocker,
    private val nanoTime: () -> Long = ::monotonicNanos,
) : FrameSource {
    private val lock = NSLock()

    // Under [lock].
    private val registrations = mutableListOf<Registration>()
    private var heldOverall = 0

    /** Whether the output is attached to the session, as the session queue last set it. */
    private val attached = atomic(false)
    private val formatRefusalLogged = atomic(false)

    internal val outputAttached: Boolean get() = attached.value

    private val outputQueue = dispatch_queue_create("com.kashif.cameraK.frames", null)
    private val delegate = FrameOutputDelegate(this)
    private val output: AVCaptureVideoDataOutput by lazy { makeOutput() }

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration {
        if (SESSION_FORMAT !in spec.acceptedFormats) {
            CameraKLogger.w(TAG, "CameraK: a frame registration cannot read YUV_420, the iOS analysis format; rejected")
            return RejectedRegistration
        }
        val registration = Registration(spec, analyzer)
        locked { registrations += registration }
        if (attached.value) registration.activate(true)
        custom.afterQueuedSessionWork { guarded("attaching the frame output") { reconcileOnQueue() } }
        return registration
    }

    /**
     * Session queue: the output attached or removed as the registrations and [keepOutput] want, unless
     * a recording freezes the outputs; then each registration's `active` follows the output. Before the
     * session is configured this joins the setup transaction.
     */
    internal fun reconcileOnQueue() {
        if (!custom.isConfigured) {
            custom.joinSetupOnQueue {
                guarded("attaching the frame output at setup") { applyTopology(inTransaction = true) }
            }
            return
        }
        applyTopology(inTransaction = false)
    }

    private fun applyTopology(inTransaction: Boolean) {
        val ops = custom.sessionOps ?: return
        val wanted = keepOutput || locked { registrations.isNotEmpty() }
        val present = output in ops.outputs
        if (wanted != present && !recordingActive()) {
            if (!inTransaction) ops.beginConfiguration()
            try {
                when {
                    !wanted -> ops.removeOutput(output)
                    ops.canAddOutput(output) -> ops.addOutput(output)
                    else -> CameraKLogger.w(TAG, "CameraK: the session refused the frame output; frames wait for it")
                }
            } finally {
                if (!inTransaction) ops.commitConfiguration()
            }
        }
        val now = output in ops.outputs
        attached.value = now
        locked { registrations.toList() }.forEach { it.activate(now) }
    }

    /** The output's queue: one sample buffer from [input], fanned out to every admitted registration. */
    internal fun deliver(sampleBuffer: CMSampleBufferRef?, input: AVCaptureInput?) {
        onSampleBuffer(input)
        val buffer = sampleBuffer ?: return
        val pixels = CMSampleBufferGetImageBuffer(buffer) ?: return
        val format = CVPixelBufferGetPixelFormatType(pixels)
        if (format != kCVPixelFormatType_420YpCbCr8BiPlanarFullRange &&
            format != kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        ) {
            if (formatRefusalLogged.compareAndSet(expect = false, update = true)) {
                CameraKLogger.e(
                    TAG,
                    "CameraK: the frame output delivered pixel format $format, not 420f; frames are skipped",
                )
            }
            return
        }
        val targets = locked { registrations.filter { it.isActive } }
        if (targets.isEmpty()) return
        val timestamp = (CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(buffer)) * NANOS_PER_SECOND).toLong()
        val rotation = rotationDegrees()
        for (registration in targets) {
            val lease = registration.gate.tryEnter() ?: continue
            val frame = AppleFrame(FrameData(this, registration, buffer, pixels, rotation, timestamp, lease))
            CFRetain(buffer)
            val queue = registration.queue
            if (queue == null) analyze(frame) else dispatch_async(queue) { analyze(frame) }
        }
    }

    /** The registration's queue (or the output's): analyze() under the base-address lock, then one finally. */
    private fun analyze(frame: AppleFrame) {
        val data = frame.data
        autoreleasepool {
            var isLocked = false
            try {
                isLocked = locker.lock(data.pixels)
                if (!isLocked) {
                    CameraKLogger.w(TAG, "CameraK: Core Video refused to lock a frame; it is skipped")
                    data.lease.release()
                } else if (data.registration.closed.value) {
                    data.lease.release()
                } else {
                    data.lease.deliver { data.registration.analyzer.analyze(frame) }
                }
            } finally {
                frame.end()
                if (isLocked) locker.unlock(data.pixels)
                CFRelease(data.buffer)
            }
        }
    }

    /** The output's queue: AVFoundation dropped a late sample buffer. */
    internal fun dropped() {
        locked { registrations.filter { it.isActive } }.forEach { it.drops.incrementAndGet() }
    }

    /**
     * A retain of [data]'s frame under [handle]: the sample buffer itself while its registration holds
     * none and fewer than [MAX_HELD_BUFFERS] are held overall, otherwise a copy of [luma]'s luma.
     */
    internal fun retain(data: FrameData, handle: AnalyzerRetain, luma: () -> ByteArray): RetainedFrame {
        val holds = locked {
            val registration = data.registration
            if (registration.heldBuffers < 1 && heldOverall < MAX_HELD_BUFFERS) {
                registration.heldBuffers++
                heldOverall++
                true
            } else {
                false
            }
        }
        if (!holds) return LumaCopyFrame(data, luma(), handle)
        CFRetain(data.buffer)
        if (!locker.lock(data.pixels)) {
            CameraKLogger.w(TAG, "CameraK: Core Video refused to lock a retained frame; its luma is copied instead")
            releaseHeld(data)
            return LumaCopyFrame(data, luma(), handle)
        }
        return HeldFrame(data, handle)
    }

    /** A held frame closed: its lock, its sample buffer and its share of the cap are given back. */
    internal fun releaseHeld(data: FrameData, wasLocked: Boolean = false) {
        if (wasLocked) locker.unlock(data.pixels)
        CFRelease(data.buffer)
        locked {
            data.registration.heldBuffers--
            heldOverall--
        }
    }

    private fun makeOutput(): AVCaptureVideoDataOutput = AVCaptureVideoDataOutput().apply {
        val format = NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        // An unavailable format would raise an NSException, which no Kotlin catch can stop.
        if (availableVideoCVPixelFormatTypes.contains(format)) {
            videoSettings = mapOf<Any?, Any?>(pixelFormatKey() to format)
        } else {
            CameraKLogger.w(TAG, "CameraK: the frame output does not list 420f; it keeps its default format")
        }
        alwaysDiscardsLateVideoFrames = true
        setSampleBufferDelegate(delegate, outputQueue)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "CameraK: $what failed", e)
        }
    }

    internal inner class Registration(spec: FrameSpec, val analyzer: FrameAnalyzer) :
        FrameRegistration,
        AppleFrameRegistration {
        val gate = AnalyzerGate(spec.maxFps, nanoTime, spec.maxRetained)
        val synchronous = spec.synchronous
        val queue: dispatch_queue_t? =
            if (synchronous) null else dispatch_queue_create("com.kashif.cameraK.analyzer", null)
        val closed = atomic(false)
        val drops = atomic(0)

        // Under the source's lock.
        var heldBuffers = 0

        private val _active = MutableStateFlow(false)
        override val active: StateFlow<Boolean> = _active.asStateFlow()
        override val rejection: CameraKError? = null
        override val droppedFrames: Int get() = drops.value

        val isActive: Boolean get() = _active.value && !closed.value

        fun activate(on: Boolean) {
            if (!closed.value) _active.value = on
        }

        override fun close() {
            if (!closed.compareAndSet(expect = false, update = true)) return
            _active.value = false
            locked { registrations -= this }
            custom.afterQueuedSessionWork { guarded("removing the frame output") { reconcileOnQueue() } }
        }
    }

    private object RejectedRegistration : FrameRegistration {
        override val active: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val rejection: CameraKError = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)

        override fun close() = Unit
    }

    private companion object {
        const val TAG = "CameraK"
        const val MAX_HELD_BUFFERS = 2
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val SESSION_FORMAT = FrameFormat.YUV_420
    }
}

/** One delivered sample buffer, as one registration got it. */
internal class FrameData(
    val source: AppleFrameSource,
    val registration: AppleFrameSource.Registration,
    val buffer: CMSampleBufferRef,
    val pixels: CVPixelBufferRef,
    val rotation: Int,
    val timestamp: Long,
    val lease: AnalyzerLease,
)

/**
 * The video data output's sample-buffer delegate. AVFoundation calls it on the output's queue; the
 * input a buffer came through is its connection's first input port's.
 */
internal class FrameOutputDelegate(private val source: AppleFrameSource) :
    NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        deliver(didOutputSampleBuffer, (fromConnection.inputPorts.firstOrNull() as? AVCaptureInputPort)?.input)
    }

    @ObjCSignatureOverride
    override fun captureOutput(
        output: AVCaptureOutput,
        didDropSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        drop()
    }

    /** One sample buffer through [input]; nothing but a fatal analyzer failure leaves this call. */
    fun deliver(sampleBuffer: CMSampleBufferRef?, input: AVCaptureInput?) {
        try {
            source.deliver(sampleBuffer, input)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: delivering a frame failed", e)
        }
    }

    fun drop() = source.dropped()
}

private fun pixelFormatKey(): String = CFBridgingRelease(CFRetain(kCVPixelBufferPixelFormatTypeKey)) as String

private val monotonicStart = TimeSource.Monotonic.markNow()

private fun monotonicNanos(): Long = monotonicStart.elapsedNow().inWholeNanoseconds
