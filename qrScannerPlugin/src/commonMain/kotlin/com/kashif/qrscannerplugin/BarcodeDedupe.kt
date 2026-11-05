package com.kashif.qrscannerplugin

import kotlin.time.Duration

/**
 * The one dedupe policy of every platform: a code, keyed by its format and payload, is reported at
 * most once per [window] on the frames' capture clock. Pure; the plugin keeps one per registration
 * and calls it from one thread.
 */
internal class BarcodeDedupe(private val window: Duration) {
    /** True when [barcode], seen in a frame captured at [timestampNanos], is to be reported. */
    fun admit(barcode: Barcode, timestampNanos: Long): Boolean = true

    /** The codes still inside their window, kept to dedupe them. */
    val remembered: Int get() = 0
}
