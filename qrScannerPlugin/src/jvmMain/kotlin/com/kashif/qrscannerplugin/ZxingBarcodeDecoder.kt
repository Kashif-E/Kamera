package com.kashif.qrscannerplugin

internal actual fun createBarcodeDecoder(formats: Set<BarcodeFormat>): BarcodeDecoder = ZxingBarcodeDecoder(formats)

/** ZXing over the plugin's luma, on Android and desktop. */
internal class ZxingBarcodeDecoder(
    private val formats: Set<BarcodeFormat>,
    private val reader: LumaReader = ZxingLumaReader(formats),
) : BarcodeDecoder {
    override fun decode(image: LumaImage): List<Barcode> = emptyList()

    /** The luma ZXing reads for [image]: its area, upright, downscaled to at most 1280 px a side. */
    fun prepare(image: LumaImage): LumaSource = LumaSource(ByteArray(0), 0, 0)
}

/** [height] rows of [width] bytes of luma, in [data], which the decoder reuses for the next frame. */
internal class LumaSource(val data: ByteArray, val width: Int, val height: Int)

/** One code a [LumaReader] found: its corners or end points in source pixels, as x, y pairs. */
internal class ReadCode(val text: String?, val bytes: ByteArray?, val format: BarcodeFormat, val points: FloatArray)

/** One ZXing pass over a luma source, so a test can count and inspect passes. */
internal fun interface LumaReader {
    /**
     * The codes in [source]; empty when it holds none readable.
     *
     * @param tryHarder Spend more time looking for a hard-to-read code.
     * @param inverted Look for a light-on-dark code.
     */
    fun read(source: LumaSource, tryHarder: Boolean, inverted: Boolean): List<ReadCode>
}

/** [LumaReader] over ZXing's MultiFormatReader, reading [formats]. */
internal class ZxingLumaReader(private val formats: Set<BarcodeFormat>) : LumaReader {
    override fun read(source: LumaSource, tryHarder: Boolean, inverted: Boolean): List<ReadCode> = emptyList()
}
