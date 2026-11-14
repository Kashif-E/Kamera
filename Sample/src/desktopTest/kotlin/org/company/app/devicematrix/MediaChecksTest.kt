package org.company.app.devicematrix

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The checks the matrix runs on a capture or a recording, over hand-built headers. */
class MediaChecksTest {
    @Test
    fun theFormatComesFromTheMagicBytes() {
        assertEquals(ImageFormat.JPEG, sniffImageFormat(testJpeg(4032, 3024)))
        assertEquals(ImageFormat.PNG, sniffImageFormat(testPng(4032, 3024)))
        assertEquals(ImageFormat.HEIC, sniffImageFormat(testHeic("heic")))
        assertEquals(ImageFormat.HEIC, sniffImageFormat(testHeic("mif1")))
        assertNull(sniffImageFormat(testMp4(durationUnits = 3000, timescale = 1000)), "an MP4 is no image")
        assertNull(sniffImageFormat(ByteArray(0)))
        assertNull(sniffImageFormat(byteArrayOf(0x47, 0x49, 0x46, 0x38)), "GIF")
    }

    @Test
    fun aJpegsSize_isItsFrameHeaders_notTheExifThumbnails() {
        assertEquals(MatrixSize(4032, 3024), imageSize(testJpeg(4032, 3024)))
        assertEquals(MatrixSize(3024, 4032), imageSize(testJpeg(3024, 4032)))
    }

    @Test
    fun pngAndHeicSizes() {
        assertEquals(MatrixSize(1920, 1080), imageSize(testPng(1920, 1080)))
        assertEquals(MatrixSize(4032, 3024), imageSize(testHeic("heic")), "the largest ispe: the grid, not a tile")
        assertNull(imageSize(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun theExifOrientationIsRead() {
        assertEquals(6, jpegExifOrientation(testJpeg(4032, 3024, orientation = 6)))
        assertEquals(2, jpegExifOrientation(testJpeg(4032, 3024, orientation = 2, littleEndian = true)))
        assertNull(jpegExifOrientation(testJpeg(4032, 3024, orientation = null)))
        assertNull(jpegExifOrientation(testPng(4, 3)))
    }

    @Suppress("DEPRECATION")
    @Test
    fun theRatioMatchesInEitherOrientation_withinOnePercent() {
        assertTrue(ratioMatches(4000, 3000, AspectRatio.RATIO_4_3))
        assertTrue(ratioMatches(3000, 4000, AspectRatio.RATIO_4_3))
        assertFalse(ratioMatches(1920, 1080, AspectRatio.RATIO_4_3))
        assertTrue(ratioMatches(1920, 1080, AspectRatio.RATIO_16_9))
        assertTrue(ratioMatches(1080, 1920, AspectRatio.RATIO_9_16))
        assertTrue(ratioMatches(1920, 1080, AspectRatio.RATIO_9_16), "9:16 is 16:9 on every platform")
        assertTrue(ratioMatches(1920, 1088, AspectRatio.RATIO_16_9), "0.74% off")
        assertFalse(ratioMatches(1920, 1100, AspectRatio.RATIO_16_9), "1.8% off")
        assertTrue(ratioMatches(3024, 3024, AspectRatio.RATIO_1_1))
        assertTrue(ratioMatches(3024, 3000, AspectRatio.RATIO_1_1), "0.8% off a square")
        assertFalse(ratioMatches(0, 0, AspectRatio.RATIO_1_1))
    }

    @Test
    fun anMp4sBrandAndDuration() {
        val v0 = testMp4(durationUnits = 3000, timescale = 1000)
        assertEquals("isom", videoBrand(v0))
        assertEquals(3.0, mp4DurationSeconds(v0.size.toLong(), v0::range))

        val v1 = testMp4(durationUnits = 1_800_000, timescale = 600_000, version = 1, brand = "qt  ")
        assertEquals("qt  ", videoBrand(v1))
        assertEquals(3.0, mp4DurationSeconds(v1.size.toLong(), v1::range))

        // The moov is the last 48 bytes: its header, a 12-byte udta and a 28-byte mvhd.
        val noMoov = v0.copyOf(v0.size - 48)
        assertNull(mp4DurationSeconds(noMoov.size.toLong(), noMoov::range))
        assertNull(videoBrand(testJpeg(4, 3)))
    }

    @Test
    fun anMp4sTracksAreItsHandlersAndMediaDurations() {
        val file = testMp4(
            durationUnits = 5333,
            timescale = 1000,
            tracks = listOf(Triple("vide", 15_360L, 81_920L), Triple("soun", 48_000L, 254_880L)),
        )

        assertEquals(
            listOf(Mp4Track("vide", 81_920.0 / 15_360), Mp4Track("soun", 5.31)),
            mp4Tracks(file.size.toLong(), file::range),
        )
        val noMoov = testMp4(durationUnits = 3000, timescale = 1000).let { it.copyOf(it.size - 48) }
        assertEquals(emptyList(), mp4Tracks(noMoov.size.toLong(), noMoov::range))
    }

    @Test
    fun aRecordingHasAnAudioTrackAsLongAsItsVideoExactlyWhenAudioWasAsked() {
        val video = Mp4Track("vide", 5.333)

        assertNull(audioTrackProblem(listOf(video, Mp4Track("soun", 5.31)), askedAudio = true))
        assertNull(audioTrackProblem(listOf(video), askedAudio = false))
        assertEquals("no audio track", audioTrackProblem(listOf(video), askedAudio = true))
        assertEquals(
            "an audio track, though audio was not asked",
            audioTrackProblem(listOf(video, Mp4Track("soun", 5.31)), askedAudio = false),
        )
        assertEquals(
            "audio 4.80 s against video 5.33 s",
            audioTrackProblem(listOf(video, Mp4Track("soun", 4.8)), askedAudio = true),
        )
        assertEquals("no video track", audioTrackProblem(listOf(Mp4Track("soun", 5.31)), askedAudio = true))
    }

    @Test
    fun anAudioTrackWhoseLoudestSampleIsUnderOne16BitStepIsDigitalSilence() {
        assertNull(digitalSilenceProblem(null), "the platform cannot decode it")
        assertNull(digitalSilenceProblem(-48.7), "a quiet room")
        assertNull(digitalSilenceProblem(-89.0))
        assertEquals("the audio is digital silence, its loudest sample -120.0 dBFS", digitalSilenceProblem(-120.0))
        assertEquals("the audio is digital silence, every sample zero", digitalSilenceProblem(Double.NEGATIVE_INFINITY))
    }
}

private fun ByteArray.range(offset: Long, length: Int): ByteArray =
    copyOfRange(offset.toInt().coerceAtMost(size), (offset + length).toInt().coerceAtMost(size))

private class Bytes {
    private val out = java.io.ByteArrayOutputStream()

    fun u8(vararg values: Int) = apply { values.forEach { out.write(it and 0xFF) } }

    fun u16(value: Int, little: Boolean = false) =
        apply { if (little) u8(value, value shr 8) else u8(value shr 8, value) }

    fun u32(value: Long, little: Boolean = false) = apply {
        val bytes = (0 until 4).map { ((value shr (8 * (3 - it))) and 0xFF).toInt() }
        u8(*(if (little) bytes.reversed() else bytes).toIntArray())
    }

    fun u64(value: Long) = u32(value ushr 32).u32(value and 0xFFFFFFFFL)

    fun ascii(text: String) = apply { out.write(text.encodeToByteArray()) }

    fun bytes(data: ByteArray) = apply { out.write(data) }

    fun build(): ByteArray = out.toByteArray()
}

private fun box(type: String, body: ByteArray): ByteArray = Bytes().u32(8L + body.size).ascii(type).bytes(body).build()

/** A JPEG with an EXIF APP1 that holds a thumbnail's frame header, then the image's own SOF0. */
internal fun testJpeg(width: Int, height: Int, orientation: Int? = 1, littleEndian: Boolean = false): ByteArray {
    val tiff = Bytes().apply {
        if (littleEndian) ascii("II").u16(42, little = true) else ascii("MM").u16(42)
        u32(8, littleEndian)
        if (orientation == null) {
            u16(0, littleEndian)
        } else {
            u16(1, littleEndian)
            u16(0x0112, littleEndian).u16(3, littleEndian).u32(1, littleEndian).u16(orientation, littleEndian).u16(0)
        }
        u32(0, littleEndian)
        // The thumbnail's own frame header, which the size must not be read from.
        u8(0xFF, 0xD8, 0xFF, 0xC0).u16(17).u8(8).u16(120).u16(160).u8(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
    }.build()
    val app1 = Bytes().ascii("Exif").u8(0, 0).bytes(tiff).build()
    return Bytes()
        .u8(0xFF, 0xD8)
        .u8(0xFF, 0xE1).u16(app1.size + 2).bytes(app1)
        .u8(0xFF, 0xDB).u16(4).u8(0, 0)
        .u8(0xFF, 0xC0).u16(17).u8(8).u16(height).u16(width).u8(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        .u8(0xFF, 0xDA).u16(8).u8(1, 1, 0, 0, 0x3F, 0)
        .u8(0x12, 0x34, 0xFF, 0xD9)
        .build()
}

internal fun testPng(width: Int, height: Int): ByteArray = Bytes()
    .u8(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    .u32(13).ascii("IHDR").u32(width.toLong()).u32(height.toLong()).u8(8, 6, 0, 0, 0)
    .u32(0)
    .build()

/** A HEIC whose meta box has a tile's, the grid's and a thumbnail's ispe. */
internal fun testHeic(brand: String): ByteArray {
    fun ispe(w: Int, h: Int) = box("ispe", Bytes().u32(0).u32(w.toLong()).u32(h.toLong()).build())
    val ftyp = box("ftyp", Bytes().ascii(brand).u32(0).ascii("mif1").ascii("heic").build())
    val meta = box("meta", Bytes().u32(0).bytes(ispe(512, 512)).bytes(ispe(4032, 3024)).bytes(ispe(320, 240)).build())
    return Bytes().bytes(ftyp).bytes(meta).build()
}

/** ftyp, an mdat, then a moov whose mvhd follows a udta box, as a muxer writes it. */
internal fun testMp4(
    durationUnits: Long,
    timescale: Long,
    version: Int = 0,
    brand: String = "isom",
    tracks: List<Triple<String, Long, Long>> = emptyList(),
): ByteArray {
    val ftyp = box("ftyp", Bytes().ascii(brand).u32(0x200).ascii("isom").ascii("mp42").build())
    val mdat = box("mdat", ByteArray(100))
    val mvhd = if (version == 0) {
        box("mvhd", Bytes().u8(0, 0, 0, 0).u32(0).u32(0).u32(timescale).u32(durationUnits).build())
    } else {
        box("mvhd", Bytes().u8(1, 0, 0, 0).u64(0).u64(0).u32(timescale).u64(durationUnits).build())
    }
    // Each track: handler, media timescale, media duration; an mdhd before the hdlr, as muxers write them.
    val traks = tracks.map { (handler, scale, units) ->
        val mdhd = box("mdhd", Bytes().u32(0).u32(0).u32(0).u32(scale).u32(units).u32(0).build())
        val hdlr = box("hdlr", Bytes().u32(0).u32(0).ascii(handler).u32(0).u32(0).u32(0).u8(0).build())
        val mdia = box("mdia", Bytes().bytes(mdhd).bytes(hdlr).build())
        box("trak", Bytes().bytes(box("tkhd", ByteArray(84))).bytes(mdia).build())
    }
    val moov = box(
        "moov",
        Bytes().bytes(box("udta", ByteArray(4))).bytes(mvhd).apply { traks.forEach { bytes(it) } }.build(),
    )
    return Bytes().bytes(ftyp).bytes(mdat).bytes(moov).build()
}
