@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.DirectExecutor
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.jpeg
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CameraX's file output always encodes JPEG. A PNG configuration still gets a real PNG (§6.3 lists PNG
 * for Android, and start never accepts a format it ignores): the engine decodes CameraX's JPEG, turns
 * the pixels by its EXIF orientation, which a PNG cannot carry, writes the PNG on the image-processing
 * executor and deletes the JPEG.
 */
@RunWith(RobolectricTestRunner::class)
class PngCaptureTest {
    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pngConfiguredCaptureIsARealPngFile() = runTest {
        val (image, platform) = pngCapture(jpegOf = { dir -> jpeg(dir, 16, 8) { _, _ -> Color.GREEN } })

        assertTrue(image.path.endsWith(".png"), "the PNG capture is named ${image.path}")
        assertEquals(ImageFormat.PNG, image.format)
        val bytes = File(image.path).readBytes()
        assertEquals(PNG_SIGNATURE, bytes.take(PNG_SIGNATURE.size), "the file does not start with the PNG signature")
        assertFalse(platform.backend.captures.single().outputFile.exists(), "CameraX's JPEG was kept beside the PNG")
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pngKeepsTheJpegsExifOrientationInItsPixels() = runTest {
        // A 64x32 JPEG, red on its left half and blue on its right, tagged to be turned 90° clockwise.
        val (image, _) = pngCapture(
            jpegOf = { dir ->
                jpeg(dir, 64, 32, ExifInterface.ORIENTATION_ROTATE_90) { x, _ -> if (x < 32) Color.RED else Color.BLUE }
            },
        )

        assertEquals(32 to 64, image.width to image.height, "the PNG's size is not the turned JPEG's")
        val png = checkNotNull(BitmapFactory.decodeFile(image.path)) { "the PNG does not decode" }
        assertEquals(32 to 64, png.width to png.height)
        // Turned clockwise, the JPEG's left half is the PNG's top half.
        assertNear(Color.RED, png.getPixel(16, 8), "the PNG's top")
        assertNear(Color.BLUE, png.getPixel(16, 56), "the PNG's bottom")
    }

    private suspend fun TestScope.pngCapture(jpegOf: (File) -> ByteArray): Pair<CapturedImage, FakeAndroidPlatform> {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(
            testScheduler,
            CameraConfiguration { imageFormat = ImageFormat.PNG },
            imageProcessing = DirectExecutor(),
        )
        open(platform, engine)
        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.single().deliverSaved(jpegOf(platform.context.cacheDir))
        platform.settle(testScheduler)
        val image = assertIs<CameraKResult.Success<CapturedImage>>(capture.await(), "the PNG capture").value
        engine.closeAndJoin()
        return image to platform
    }

    private fun assertNear(expected: Int, actual: Int, what: String) {
        val channels = listOf(Color::red, Color::green, Color::blue)
        assertTrue(
            channels.all { kotlin.math.abs(it(expected) - it(actual)) <= COLOUR_TOLERANCE },
            "$what is ${Integer.toHexString(actual)}, not ${Integer.toHexString(expected)}",
        )
    }

    private companion object {
        val PNG_SIGNATURE = listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)

        // JPEG at quality 95 moves a flat colour a few levels; well inside a tenth of the range.
        const val COLOUR_TOLERANCE = 24
    }
}
