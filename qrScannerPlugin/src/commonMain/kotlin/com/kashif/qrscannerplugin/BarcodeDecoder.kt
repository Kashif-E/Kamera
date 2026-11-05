package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect

/**
 * One frame's luma, as the plugin hands it to a decoder: [luma] holds [height] rows of [width]
 * bytes, without padding, and the decoder reads only [area]. The plugin reuses [luma] for the next
 * frame, so a decoder must not keep it past [BarcodeDecoder.decode].
 *
 * @property area The rectangle to decode, in frame pixels: the frame's crop rect intersected with
 *   the configured region. Never empty.
 * @property rotationDegrees The frame's clockwise rotation to upright.
 */
internal class LumaImage(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val area: FrameRect,
    val rotationDegrees: Int,
)

/**
 * A platform barcode decoder. The plugin calls it from one thread at a time, on its frame
 * registration's worker.
 */
internal fun interface BarcodeDecoder {
    /**
     * The barcodes found in [image]'s area, with bounding boxes in frame pixels; empty when there
     * is none, which is no failure.
     *
     * @throws Exception on a failure other than "nothing readable here"; the plugin then fails.
     */
    fun decode(image: LumaImage): List<Barcode>
}

/** Makes the decoder for one frame registration. */
internal fun interface BarcodeDecoderFactory {
    fun create(formats: Set<BarcodeFormat>): BarcodeDecoder
}

/** ZXing on Android and desktop, Vision on iOS. */
internal expect fun createBarcodeDecoder(formats: Set<BarcodeFormat>): BarcodeDecoder
