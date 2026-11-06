package com.kashif.ocrplugin

import com.kashif.cameraK.result.CameraKResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One-shot OCR hands Vision the still's own pixels, decoded once from its file, upright, at the
 * accurate level by default.
 */
@OptIn(ExperimentalForeignApi::class)
class OneShotOcrImageTest {
    @Test
    fun vision_getsTheFilesPixelsDecodedOnce() = runBlocking<Unit> {
        val bitmap = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }
        Canvas(bitmap).apply {
            clear(RED)
            drawRect(Rect.makeXYWH(0f, 0f, WIDTH / 2f, HEIGHT.toFloat()), Paint().apply { color = GREEN })
        }
        val path = pngFile(bitmap)
        var size = 0 to 0
        var orientation = 0u
        var level = -1L
        var left = emptyList<Int>()
        var right = emptyList<Int>()
        try {
            val plugin = OcrPlugin(
                OcrConfig(),
                AppleTextRecognizers(
                    stillPerformer = { image, imageOrientation, request ->
                        size = CGImageGetWidth(image).toInt() to CGImageGetHeight(image).toInt()
                        orientation = imageOrientation
                        level = request.recognitionLevel
                        val rgba = rgbaOf(image)
                        left = rgba.pixel(2, 3)
                        right = rgba.pixel(WIDTH - 3, HEIGHT - 4)
                        request.completionHandler?.invoke(request, null)
                        null
                    },
                ),
            )

            val result = withTimeout(10_000) { plugin.recognize(capture(path)) }

            assertIs<CameraKResult.Success<RecognizedText>>(result)
        } finally {
            deleteFile(path)
        }
        assertEquals(WIDTH to HEIGHT, size)
        assertEquals(1u, orientation, "orientation of a PNG with no orientation")
        assertEquals(VNRequestTextRecognitionLevelAccurate, level, "the default one-shot level")
        assertClose(listOf(10, 220, 90, 255), left, "left half")
        assertClose(listOf(200, 30, 60, 255), right, "right half")
    }

    private fun assertClose(expected: List<Int>, actual: List<Int>, where: String) {
        // Drawing into the sRGB test context from an image in another RGB space may round a channel by
        // a level or two; a wrong channel order or alpha is off by far more.
        assertTrue(
            expected.size == actual.size && expected.zip(actual).all { (e, a) -> abs(e - a) <= 2 },
            "$where: expected RGBA $expected, Vision saw $actual",
        )
    }

    /** Draws [image] into an sRGB RGBA context and returns its bytes, top row first. */
    private fun rgbaOf(image: CGImageRef): Rgba = memScoped {
        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        val bytes = allocArray<UByteVar>(width * height * 4)
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        val context =
            CGBitmapContextCreate(
                bytes,
                width.convert(),
                height.convert(),
                8u,
                (width * 4).convert(),
                space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
        CGContextRelease(context)
        CGColorSpaceRelease(space)
        Rgba(width, List(width * height * 4) { bytes[it].toInt() })
    }

    private class Rgba(val width: Int, val bytes: List<Int>) {
        fun pixel(x: Int, y: Int): List<Int> = bytes.subList((y * width + x) * 4, (y * width + x) * 4 + 4)
    }

    private companion object {
        const val WIDTH = 16
        const val HEIGHT = 12
        val RED = Color.makeRGB(200, 30, 60)
        val GREEN = Color.makeRGB(10, 220, 90)
    }
}
