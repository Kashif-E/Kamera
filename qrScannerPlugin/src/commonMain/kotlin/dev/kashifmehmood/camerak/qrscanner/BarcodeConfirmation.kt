package dev.kashifmehmood.camerak.qrscanner

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A code, keyed by its format and payload, is confirmed once it has been read in [confirmations]
 * frames whose reads are younger than [window] on the frames' capture clock. A pattern that only looks
 * like a code, such as a ventilation grille, decodes to a different misread from frame to frame and
 * gathers no second read (#190). Pure; the plugin keeps one per registration and calls it from one thread.
 */
internal class BarcodeConfirmation(private val confirmations: Int, window: Duration) {
    private val windowNanos = window.inWholeNanoseconds
    private val reads = HashMap<BarcodeKey, ArrayDeque<Long>>()

    /** True when [barcode], read in a frame captured at [timestampNanos], has now been read in enough frames. */
    fun sighted(barcode: Barcode, timestampNanos: Long): Boolean {
        for (times in reads.values) {
            while (times.isNotEmpty() && timestampNanos - times.first() >= windowNanos) times.removeFirst()
        }
        reads.values.removeAll { it.isEmpty() }
        val times = reads.getOrPut(BarcodeKey(barcode)) { ArrayDeque() }
        if (times.lastOrNull() != timestampNanos) times.addLast(timestampNanos)
        return times.size >= confirmations
    }

    /** The codes with a read still inside the window. */
    val remembered: Int get() = reads.size

    companion object {
        /**
         * A second, or [confirmations] frame intervals at [maxFps] when those are longer: the reads
         * may skip a frame between them.
         */
        fun window(confirmations: Int, maxFps: Float): Duration =
            maxOf(1.seconds, (confirmations / maxFps.toDouble()).seconds)
    }
}
