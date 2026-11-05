package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the QR scanner decodes, built with [QRScannerConfig]. Compares by value.
 *
 * @property formats The symbologies to read. QR codes only by default; every extra format costs
 *   decode time on each frame.
 * @property region Decode only inside this rectangle, in frame pixels, intersected with the frame's
 *   crop rect. Null decodes the whole crop rect.
 * @property dedupeWindow The same code (format and payload) is reported at most once per window,
 *   measured on the frames' capture clock. Zero reports it on every decoded frame.
 * @property maxFps The most frames a second the scanner decodes. The frame source skips the others
 *   before copying them. Five by default.
 */
public class QRScannerConfig private constructor(builder: Builder) {
    public val formats: Set<BarcodeFormat> = builder.formats.toSet()
    public val region: FrameRect? = builder.region
    public val dedupeWindow: Duration = builder.dedupeWindow
    public val maxFps: Float = builder.maxFps

    /** The configuration's fields, each starting at the configuration it copies, or the default. */
    public class Builder internal constructor(from: QRScannerConfig?) {
        public var formats: Set<BarcodeFormat> = from?.formats ?: setOf(BarcodeFormat.QR_CODE)
        public var region: FrameRect? = from?.region
        public var dedupeWindow: Duration = from?.dedupeWindow ?: DEFAULT_DEDUPE_WINDOW
        public var maxFps: Float = from?.maxFps ?: DEFAULT_MAX_FPS

        internal fun build(): QRScannerConfig {
            require(formats.isNotEmpty()) { "formats must name at least one barcode format" }
            require(maxFps > 0f && maxFps.isFinite()) { "maxFps must be a positive finite number, not $maxFps" }
            require(!dedupeWindow.isNegative()) { "dedupeWindow must not be negative, not $dedupeWindow" }
            region?.let {
                require(it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top) {
                    "region must be a non-empty rectangle inside the frame, not [${it.left}, ${it.top}, ${it.right}, ${it.bottom})"
                }
            }
            return QRScannerConfig(this)
        }
    }

    /** A copy with the fields [block] sets changed. */
    public fun copy(block: Builder.() -> Unit): QRScannerConfig = Builder(this).apply(block).build()

    override fun equals(other: Any?): Boolean = other is QRScannerConfig &&
        formats == other.formats &&
        regionKey() == other.regionKey() &&
        dedupeWindow == other.dedupeWindow &&
        maxFps == other.maxFps

    override fun hashCode(): Int {
        var result = formats.hashCode()
        result = 31 * result + regionKey().hashCode()
        result = 31 * result + dedupeWindow.hashCode()
        result = 31 * result + maxFps.hashCode()
        return result
    }

    override fun toString(): String = "QRScannerConfig(formats=$formats, region=${regionKey()}, " +
        "dedupeWindow=$dedupeWindow, maxFps=$maxFps)"

    private fun regionKey(): List<Int>? = region?.let { listOf(it.left, it.top, it.right, it.bottom) }

    internal companion object {
        val DEFAULT_DEDUPE_WINDOW: Duration = 1.seconds

        // 1.x decoded at most once per 200 ms.
        const val DEFAULT_MAX_FPS: Float = 5f
    }
}

/**
 * Builds a [QRScannerConfig]. Every field has its default unless [block] sets it.
 *
 * @throws IllegalArgumentException when [QRScannerConfig.formats] is empty, [QRScannerConfig.maxFps]
 *   is not a positive finite number, [QRScannerConfig.dedupeWindow] is negative, or
 *   [QRScannerConfig.region] is empty or starts left of or above the frame.
 */
public fun QRScannerConfig(block: QRScannerConfig.Builder.() -> Unit = {}): QRScannerConfig =
    QRScannerConfig.Builder(null).apply(block).build()
