package com.kashif.cameraK.controller

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kashif.cameraK.frames.AnalyzerLease
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKLogger
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CameraK"

/** A FrameSource registration as the multiplexer sees it. */
internal interface FrameConsumer {
    /** Gets the proxy itself on the analysis thread, rather than a pooled copy on its own worker. */
    val synchronous: Boolean

    /** Delivers [proxy] on the calling thread, unless the registration's gate skips it. */
    fun deliverSynchronously(proxy: ImageProxy, meta: FrameMeta)

    /** A lease for this frame when the registration takes it, released through [onReleased]; else null. */
    fun tryEnter(onReleased: () -> Unit): AnalyzerLease?

    /** Hands [slot]'s copy to the registration's worker under [lease]. */
    fun dispatch(slot: FrameSlot, lease: AnalyzerLease)
}

/**
 * Dispatches each camera frame (design §8) on the analysis thread:
 *
 * 1. to each synchronous registration [frameConsumers] returns, in turn, with the proxy itself;
 * 2. when an asynchronous registration takes the frame, the planes are copied once into a slot from
 *    [pool], and the slot goes to each one that took it, on its own worker;
 * 3. to each 1.x consumer [consumers] returns, in turn, with its own [LegacyImageProxy] share.
 *
 * The multiplexer holds a share of its own and closes it before [analyze] returns, so the proxy
 * closes then unless a 1.x consumer still holds its share; it closes at the last share's close.
 */
internal class MultiplexingAnalyzer(
    private val consumers: () -> List<ImageAnalysis.Analyzer>,
    private val frameConsumers: () -> List<FrameConsumer> = { emptyList() },
    private val sessionFormat: FrameFormat = FrameFormat.YUV_420,
    private val pool: FramePool = FramePool(),
) : ImageAnalysis.Analyzer {
    constructor(analyzers: List<ImageAnalysis.Analyzer>) : this({ analyzers })

    override fun analyze(imageProxy: ImageProxy) {
        val legacy = consumers()
        val registered = frameConsumers()
        val shares = AtomicInteger(1 + legacy.size)
        try {
            if (registered.isNotEmpty()) deliver(imageProxy, registered)
            for (analyzer in legacy) {
                val frame = LegacyImageProxy(imageProxy, shares)
                // Runs on the analyzer executor, where an escaped exception kills the host process. A
                // failing consumer gives up its share of the frame so the others, and the stream, go on.
                try {
                    analyzer.analyze(frame)
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "Image analyzer failed", e)
                    frame.close()
                }
            }
        } finally {
            if (shares.decrementAndGet() == 0) imageProxy.close()
        }
    }

    private fun deliver(proxy: ImageProxy, registered: List<FrameConsumer>) {
        val meta = try {
            FrameMeta.of(proxy, sessionFormat)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading an analysis frame's metadata failed; the registrations skip it", e)
            return
        }
        for (consumer in registered) {
            if (consumer.synchronous) consumer.deliverSynchronously(proxy, meta)
        }
        val shared = SharedSlot(pool)
        val admitted = ArrayList<Pair<FrameConsumer, AnalyzerLease>>(registered.size)
        for (consumer in registered) {
            if (consumer.synchronous) continue
            consumer.tryEnter(shared::release)?.let { admitted += consumer to it }
        }
        if (admitted.isEmpty()) return
        val slot = try {
            pool.take().also { it.fill(proxy, meta) }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Copying an analysis frame failed; the asynchronous registrations skip it", e)
            admitted.forEach { (_, lease) -> lease.release() }
            return
        }
        shared.attach(slot, admitted.size)
        for ((consumer, lease) in admitted) consumer.dispatch(slot, lease)
    }

    /** One frame's slot, back to the pool once every registration it went to has released it. */
    private class SharedSlot(private val pool: FramePool) {
        @Volatile
        private var slot: FrameSlot? = null
        private val holders = AtomicInteger()

        fun attach(slot: FrameSlot, holders: Int) {
            this.holders.set(holders)
            this.slot = slot
        }

        fun release() {
            val taken = slot ?: return
            if (holders.decrementAndGet() == 0) pool.give(taken)
        }
    }
}
