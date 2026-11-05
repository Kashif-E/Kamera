package com.kashif.qrscannerplugin

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.utils.CameraKLogger
import java.util.EnumMap
import com.google.zxing.BarcodeFormat as ZxingFormat

internal actual fun createBarcodeDecoder(formats: Set<BarcodeFormat>): BarcodeDecoder = ZxingBarcodeDecoder(formats)

/**
 * ZXing over the plugin's luma, on Android and desktop. It cuts out the image's area, downscales
 * it by a whole factor to at most [MAX_DECODE_SIDE] px a side, turns it upright by the frame's
 * rotation, so a 1D barcode in a portrait frame lies along ZXing's scan lines (#168), and reads it.
 * ZXing's slower TRY_HARDER pass runs on every [SLOW_PASS_EVERY]th attempt, and a pass for
 * light-on-dark codes on every [INVERTED_PASS_EVERY]th one that found nothing.
 *
 * Not thread-safe: the plugin calls it from its registration's worker, and the buffer it decodes
 * from is reused on the next call.
 */
internal class ZxingBarcodeDecoder(
    formats: Set<BarcodeFormat>,
    private val reader: LumaReader = ZxingLumaReader(formats),
) : BarcodeDecoder {
    private var buffer = ByteArray(0)
    private var upright = ByteArray(0)
    private var sums = IntArray(0)
    private var attempts = 0L

    // The geometry of the last prepare: the area's origin and the downscale factor.
    private var originX = 0
    private var originY = 0
    private var scale = 1
    private var geometry = UprightGeometry(0, 0, 0)

    override fun decode(image: LumaImage): List<Barcode> {
        val source = prepare(image)
        attempts++
        val tryHarder = attempts % SLOW_PASS_EVERY == 0L
        val codes = reader.read(source, tryHarder, inverted = false).ifEmpty {
            if (attempts % INVERTED_PASS_EVERY == 0L) reader.read(source, tryHarder, inverted = true) else emptyList()
        }
        return codes.map { Barcode(it.text, it.bytes, it.format, boundingBox(it.points)) }
    }

    /** The luma ZXing reads for [image]: its area, downscaled to at most 1280 px a side, upright. */
    fun prepare(image: LumaImage): LumaSource {
        val cut = cut(image)
        geometry = UprightGeometry(cut.width, cut.height, image.rotationDegrees)
        if (geometry.quarterTurns == 0) return cut
        if (upright.size < cut.width * cut.height) upright = ByteArray(cut.width * cut.height)
        geometry.turnUpright(cut.data, upright)
        return LumaSource(upright, geometry.uprightWidth, geometry.uprightHeight)
    }

    // The area, downscaled, as the frame holds it.
    private fun cut(image: LumaImage): LumaSource {
        val area = image.area
        val areaWidth = area.right - area.left
        val areaHeight = area.bottom - area.top
        scale = (maxOf(areaWidth, areaHeight) + MAX_DECODE_SIDE - 1) / MAX_DECODE_SIDE
        originX = area.left
        originY = area.top
        if (scale == 1 && areaWidth == image.width && areaHeight == image.height) {
            return LumaSource(image.luma, image.width, image.height)
        }
        val width = areaWidth / scale
        val height = areaHeight / scale
        if (buffer.size < width * height) buffer = ByteArray(width * height)
        if (scale == 1) {
            for (y in 0 until height) {
                image.luma.copyInto(
                    buffer,
                    y * width,
                    (area.top + y) * image.width + area.left,
                    (area.top + y) * image.width + area.right,
                )
            }
        } else {
            downscale(image, width, height)
        }
        return LumaSource(buffer, width, height)
    }

    // Box-averages scale x scale blocks of the area into buffer.
    private fun downscale(image: LumaImage, width: Int, height: Int) {
        if (sums.size < width) sums = IntArray(width)
        val block = scale * scale
        for (y in 0 until height) {
            sums.fill(0, 0, width)
            for (dy in 0 until scale) {
                var i = (image.area.top + y * scale + dy) * image.width + image.area.left
                for (x in 0 until width) {
                    var sum = 0
                    repeat(scale) { sum += image.luma[i++].toInt() and 0xFF }
                    sums[x] += sum
                }
            }
            val row = y * width
            for (x in 0 until width) buffer[row + x] = (sums[x] / block).toByte()
        }
    }

    // The points' bounds in frame pixels; a 1D code's two end points get a box one source pixel thick.
    private fun boundingBox(points: FloatArray): FrameRect? {
        if (points.size < 2) return null
        val box = geometry.toImage(points)
        return FrameRect(
            originX + box.left * scale,
            originY + box.top * scale,
            originX + box.right * scale,
            originY + box.bottom * scale,
        )
    }

    private companion object {
        // 720p decodes at full size; 1080p and larger are averaged down, which keeps a code's modules
        // several pixels wide at a typical scanning distance.
        const val MAX_DECODE_SIDE = 1280

        // ZXing's TRY_HARDER pass costs several fast passes; one attempt in this many uses it.
        const val SLOW_PASS_EVERY = 5L

        // Light-on-dark codes are uncommon, and the pass costs a second full decode (#116).
        const val INVERTED_PASS_EVERY = 3L
    }
}

/** [height] rows of [width] bytes of luma, from the start of [data], which the decoder reuses for the next frame. */
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

/**
 * [LumaReader] over ZXing's MultiFormatReader, reading [formats]. "No readable code" in any of
 * ZXing's forms (not found, checksum, format) is an empty result, not a failure, and is not logged.
 */
internal class ZxingLumaReader(formats: Set<BarcodeFormat>) : LumaReader {
    private val possible = formats.mapNotNull { ZXING_FORMATS[it] }
    private val fastReader = reader(tryHarder = false)
    private val slowReader = reader(tryHarder = true)

    init {
        val ignored = formats.filterNot { it in ZXING_FORMATS }
        if (ignored.isNotEmpty()) CameraKLogger.w(TAG, "ZXing cannot read $ignored; those formats are ignored")
    }

    override fun read(source: LumaSource, tryHarder: Boolean, inverted: Boolean): List<ReadCode> {
        val luminance =
            PlanarYUVLuminanceSource(source.data, source.width, source.height, 0, 0, source.width, source.height, false)
        val reader = if (tryHarder) slowReader else fastReader
        val result = try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(if (inverted) luminance.invert() else luminance)))
        } catch (expected: ReaderException) {
            return emptyList()
        } finally {
            reader.reset()
        }
        val format = FORMATS_BY_ZXING[result.barcodeFormat] ?: return emptyList()
        return listOf(ReadCode(result.text, result.byteSegments(), format, result.points()))
    }

    private fun reader(tryHarder: Boolean) = MultiFormatReader().apply {
        setHints(
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.CHARACTER_SET, "UTF-8")
                put(DecodeHintType.POSSIBLE_FORMATS, possible)
                if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
            },
        )
    }

    private companion object {
        const val TAG = "QRScanner"

        val ZXING_FORMATS: Map<BarcodeFormat, ZxingFormat> = mapOf(
            BarcodeFormat.QR_CODE to ZxingFormat.QR_CODE,
            BarcodeFormat.AZTEC to ZxingFormat.AZTEC,
            BarcodeFormat.DATA_MATRIX to ZxingFormat.DATA_MATRIX,
            BarcodeFormat.PDF_417 to ZxingFormat.PDF_417,
            BarcodeFormat.EAN_13 to ZxingFormat.EAN_13,
            BarcodeFormat.EAN_8 to ZxingFormat.EAN_8,
            BarcodeFormat.UPC_A to ZxingFormat.UPC_A,
            BarcodeFormat.UPC_E to ZxingFormat.UPC_E,
            BarcodeFormat.CODE_128 to ZxingFormat.CODE_128,
            BarcodeFormat.CODE_39 to ZxingFormat.CODE_39,
            BarcodeFormat.CODE_93 to ZxingFormat.CODE_93,
            BarcodeFormat.CODABAR to ZxingFormat.CODABAR,
            BarcodeFormat.ITF to ZxingFormat.ITF,
        )
        val FORMATS_BY_ZXING: Map<ZxingFormat, BarcodeFormat> = ZXING_FORMATS.entries.associate { (ours, zxing) ->
            zxing to
                ours
        }
    }
}

// A QR code's byte-mode segments, joined: the payload's bytes as encoded. Null when it has none.
@Suppress("UNCHECKED_CAST")
private fun Result.byteSegments(): ByteArray? {
    val segments = resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray> ?: return null
    return segments.fold(ByteArray(0)) { all, segment -> all + segment }.takeIf { it.isNotEmpty() }
}

private fun Result.points(): FloatArray {
    val points = resultPoints?.filterNotNull() ?: return FloatArray(0)
    return FloatArray(points.size * 2) { i -> if (i % 2 == 0) points[i / 2].x else points[i / 2].y }
}
