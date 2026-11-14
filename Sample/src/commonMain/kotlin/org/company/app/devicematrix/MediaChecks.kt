package org.company.app.devicematrix

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// The ftyp major brands of HEIF stills and sequences (ISO/IEC 23008-12).
private val heifBrands = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

/** The image format [head], a file's first bytes, starts with: JPEG FF D8, PNG 89 50 4E 47, HEIC an ftyp box. */
internal fun sniffImageFormat(head: ByteArray): ImageFormat? = when {
    head.size >= 2 && head.u8(0) == 0xFF && head.u8(1) == 0xD8 -> ImageFormat.JPEG
    head.size >= 4 && head.u8(0) == 0x89 && head.ascii(1, 3) == "PNG" -> ImageFormat.PNG
    head.ascii(4, 4) == "ftyp" && head.ascii(8, 4) in heifBrands -> ImageFormat.HEIC
    else -> null
}

/** The pixel size the image's header states: JPEG's first frame header, PNG's IHDR, HEIC's largest ispe. */
internal fun imageSize(head: ByteArray): MatrixSize? = when (sniffImageFormat(head)) {
    ImageFormat.JPEG -> jpegSize(head)
    ImageFormat.PNG -> if (head.ascii(12, 4) == "IHDR") MatrixSize(head.u32(16).toInt(), head.u32(20).toInt()) else null
    ImageFormat.HEIC -> heicSize(head)
    null -> null
}

// SOF0 to SOF15, less DHT (C4), JPG (C8) and DAC (CC), which share the range.
private fun isFrameHeader(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

/**
 * Walks the JPEG's marker segments from the start, calling [segment] with each marker and the
 * offset of its payload (after the length) until it returns non-null or the scan data begins. An
 * APP1 segment is skipped whole, so the EXIF thumbnail's own frame header is never read.
 */
private fun <T> ByteArray.walkJpeg(segment: (marker: Int, payload: Int, end: Int) -> T?): T? {
    var i = 2
    while (i + 4 <= size) {
        if (u8(i) != 0xFF) return null
        val marker = u8(i + 1)
        if (marker == 0xFF) {
            i++
            continue
        }
        i += 2
        if (marker == 0x01 || marker in 0xD0..0xD8) continue
        if (marker == 0xD9 || marker == 0xDA) return null
        val length = u16(i)
        segment(marker, i + 2, i + length)?.let { return it }
        i += length
    }
    return null
}

private fun jpegSize(head: ByteArray): MatrixSize? = head.walkJpeg { marker, payload, _ ->
    if (isFrameHeader(marker) && payload + 5 <= head.size) {
        MatrixSize(width = head.u16(payload + 3), height = head.u16(payload + 1))
    } else {
        null
    }
}

// HEIF stores a size per image item (tiles, the grid, the thumbnail); the primary image is the largest.
private fun heicSize(head: ByteArray): MatrixSize? {
    var best: MatrixSize? = null
    for (i in 4..head.size - 16) {
        if (head.ascii(i, 4) != "ispe") continue
        val size = MatrixSize(head.u32(i + 8).toInt(), head.u32(i + 12).toInt())
        if (best == null || size.width.toLong() * size.height > best.width.toLong() * best.height) best = size
    }
    return best
}

/** The EXIF orientation tag (1 to 8) of a JPEG, or null when it has none. */
internal fun jpegExifOrientation(head: ByteArray): Int? {
    if (sniffImageFormat(head) != ImageFormat.JPEG) return null
    return head.walkJpeg { marker, payload, end ->
        if (marker != 0xE1 || head.ascii(payload, 4) != "Exif") return@walkJpeg null
        val tiff = payload + 6
        val little = head.ascii(tiff, 2) == "II"
        fun r16(at: Int) = if (little) head.u8(at) or (head.u8(at + 1) shl 8) else head.u16(at)
        fun r32(at: Int) = if (little) r16(at).toLong() or (r16(at + 2).toLong() shl 16) else head.u32(at)
        if (tiff + 8 > end) return@walkJpeg null
        val ifd = tiff + r32(tiff + 4).toInt()
        if (ifd + 2 > end) return@walkJpeg null
        (0 until r16(ifd))
            .map { ifd + 2 + 12 * it }
            .takeWhile { it + 12 <= end }
            .firstOrNull { r16(it) == 0x0112 }
            ?.let { r16(it + 8) }
            // An APP1 without the tag ends the search: the image has one EXIF block.
            ?: 0
    }?.takeIf { it in 1..8 }
}

/** The width-to-height ratio of [aspectRatio] in landscape. */
@Suppress("DEPRECATION")
internal fun landscapeRatio(aspectRatio: AspectRatio): Double = when (aspectRatio) {
    AspectRatio.RATIO_4_3 -> 4.0 / 3.0
    AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> 16.0 / 9.0
    AspectRatio.RATIO_1_1 -> 1.0
}

/** Whether [width] by [height] is [aspectRatio] in either orientation, within 1%. */
internal fun ratioMatches(width: Int, height: Int, aspectRatio: AspectRatio): Boolean {
    if (width <= 0 || height <= 0) return false
    val actual = max(width, height).toDouble() / min(width, height)
    return abs(actual / landscapeRatio(aspectRatio) - 1.0) <= 0.01
}

/** The major brand of an ISO base media file (MP4 or QuickTime): its ftyp box's, or null without one. */
internal fun videoBrand(head: ByteArray): String? = if (head.ascii(4, 4) == "ftyp") head.ascii(8, 4) else null

/** A box of an ISO base media file: its type, where its payload starts and where it ends. */
private class Mp4Box(val type: String?, val payload: Long, val end: Long)

/** The boxes one after another between [start] and [end], read through [read], up to a malformed header. */
private fun mp4Boxes(start: Long, end: Long, read: (offset: Long, length: Int) -> ByteArray): List<Mp4Box> {
    val boxes = mutableListOf<Mp4Box>()
    var at = start
    while (at + 8 <= end) {
        val header = read(at, 16)
        if (header.size < 8) break
        var boxSize = header.u32(0)
        var headerSize = 8L
        when (boxSize) {
            1L -> {
                if (header.size < 16) break
                boxSize = (header.u32(8) shl 32) or header.u32(12)
                headerSize = 16
            }
            0L -> boxSize = end - at
        }
        if (boxSize < headerSize) break
        boxes += Mp4Box(header.ascii(4, 4), at + headerSize, min(at + boxSize, end))
        at += boxSize
    }
    return boxes
}

private fun List<Mp4Box>.box(type: String): Mp4Box? = firstOrNull { it.type == type }

// mvhd and mdhd share their start: version, flags, two times, the timescale and the duration.
private fun headerSeconds(body: ByteArray): Double? {
    val (timescale, duration) = when {
        body.size >= 32 && body.u8(0) == 1 -> body.u32(20) to ((body.u32(24) shl 32) or body.u32(28))
        body.size >= 20 && body.u8(0) == 0 -> body.u32(12) to body.u32(16)
        else -> return null
    }
    return if (timescale == 0L) null else duration.toDouble() / timescale
}

/**
 * The duration, in seconds, of the movie's mvhd box, walking the boxes of a file of [size] bytes
 * through [read] (offset, length); null when it has no moov or mvhd.
 */
internal fun mp4DurationSeconds(size: Long, read: (offset: Long, length: Int) -> ByteArray): Double? {
    val moov = mp4Boxes(0, size, read).box("moov") ?: return null
    val mvhd = mp4Boxes(moov.payload, moov.end, read).box("mvhd") ?: return null
    return headerSeconds(read(mvhd.payload, 32))
}

/** One track of an MP4: its hdlr's handler type ("vide", "soun") and its mdhd's duration in seconds. */
internal data class Mp4Track(val handler: String, val seconds: Double)

/** The tracks of the movie in a file of [size] bytes, read through [read]; empty without a moov. */
internal fun mp4Tracks(size: Long, read: (offset: Long, length: Int) -> ByteArray): List<Mp4Track> {
    val moov = mp4Boxes(0, size, read).box("moov") ?: return emptyList()
    return mp4Boxes(moov.payload, moov.end, read).filter { it.type == "trak" }.mapNotNull { trak ->
        val mdia = mp4Boxes(trak.payload, trak.end, read).box("mdia") ?: return@mapNotNull null
        val inside = mp4Boxes(mdia.payload, mdia.end, read)
        // hdlr: version and flags, pre_defined, then the handler type.
        val handler = inside.box("hdlr")?.let { read(it.payload + 8, 4).ascii(0, 4) } ?: return@mapNotNull null
        val seconds = inside.box("mdhd")?.let { headerSeconds(read(it.payload, 32)) } ?: return@mapNotNull null
        Mp4Track(handler, seconds)
    }
}

// Each track ends on its own frame boundary: a video frame (33 ms at 30 fps) and an AAC frame (1024 samples,
// 23 ms at 44.1 kHz) apart at either end, so two tracks that start and stop together end at most about 0.1 s apart.
private const val TRACK_END_TOLERANCE_SECONDS = 0.1

/** What is wrong with the recording's audio track given whether audio was asked; null when nothing. */
internal fun audioTrackProblem(tracks: List<Mp4Track>, askedAudio: Boolean): String? {
    val video = tracks.firstOrNull { it.handler == "vide" } ?: return "no video track"
    val audio = tracks.firstOrNull { it.handler == "soun" }
    return when {
        !askedAudio -> if (audio != null) "an audio track, though audio was not asked" else null
        audio == null -> "no audio track"
        abs(audio.seconds - video.seconds) > TRACK_END_TOLERANCE_SECONDS ->
            "audio ${audio.seconds.fixed(2)} s against video ${video.seconds.fixed(2)} s"
        else -> null
    }
}

// One step of 16-bit audio, 1/32768: a live microphone's noise floor is well above it, macOS's silence is zeros.
private const val ONE_16_BIT_STEP_DBFS = -90.3

/** Whether [peakDbfs], the loudest decoded sample, says the track is digital silence; null when it is not, or unknown. */
internal fun digitalSilenceProblem(peakDbfs: Double?): String? = when {
    peakDbfs == null || peakDbfs >= ONE_16_BIT_STEP_DBFS -> null
    peakDbfs == Double.NEGATIVE_INFINITY -> "the audio is digital silence, every sample zero"
    else -> "the audio is digital silence, its loudest sample ${peakDbfs.fixed(1)} dBFS"
}

private fun Double.fixed(places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val units = (abs(this) * scale).roundToLong()
    val sign = if (this < 0 && units != 0L) "-" else ""
    return "$sign${units / scale}.${(units % scale).toString().padStart(places, '0')}"
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16(at: Int): Int = if (at + 2 <= size) (u8(at) shl 8) or u8(at + 1) else 0

private fun ByteArray.u32(at: Int): Long = if (at + 4 <= size) (u16(at).toLong() shl 16) or u16(at + 2).toLong() else 0L

private fun ByteArray.ascii(at: Int, count: Int): String? =
    if (at >= 0 && at + count <= size) decodeToString(at, at + count) else null
