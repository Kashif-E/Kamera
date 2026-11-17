package dev.kashifmehmood.camerak.qrscanner

import kotlin.time.Duration

/**
 * The one dedupe policy of every platform: a code, keyed by its format and payload, is reported at
 * most once per [window] on the frames' capture clock. Pure; the plugin keeps one per registration
 * and calls it from one thread.
 */
internal class BarcodeDedupe(window: Duration) {
    private val windowNanos = window.inWholeNanoseconds
    private val lastReported = HashMap<BarcodeKey, Long>()

    /** True when [barcode], seen in a frame captured at [timestampNanos], is to be reported. */
    fun admit(barcode: Barcode, timestampNanos: Long): Boolean {
        lastReported.entries.removeAll { (_, at) -> timestampNanos - at >= windowNanos }
        val key = BarcodeKey(barcode)
        if (key in lastReported) return false
        lastReported[key] = timestampNanos
        return true
    }

    /** The codes still inside their window, kept to dedupe them. */
    val remembered: Int get() = lastReported.size
}

/** A code as the scanner tells codes apart: by its format and payload. */
internal class BarcodeKey(barcode: Barcode) {
    private val format = barcode.format
    private val text = barcode.rawValue
    private val bytes = barcode.rawBytes

    override fun equals(other: Any?): Boolean =
        other is BarcodeKey && format == other.format && text == other.text && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * format.hashCode() + (text?.hashCode() ?: 0)) + bytes.contentHashCode()
}
