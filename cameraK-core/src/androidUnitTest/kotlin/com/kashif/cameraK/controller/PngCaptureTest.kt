@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.DirectExecutor
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.jpeg
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CameraX's file output always encodes JPEG, so on Android a capture configured as PNG holds JPEG
 * bytes. Its name and MIME type must say JPEG, and the substitution is logged, rather than a `.png`
 * registered as `image/png` that PNG decoders reject.
 *
 * From A3' the engine writes a real PNG (§6.3 lists PNG for Android, and start never accepts a format
 * it ignores): it decodes CameraX's JPEG, turns the pixels by its EXIF orientation, which a PNG cannot
 * carry, writes the PNG on the image-processing executor and deletes the JPEG.
 */
@RunWith(RobolectricTestRunner::class)
class PngCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val warnings = CopyOnWriteArrayList<String>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)

    @After
    fun tearDown() {
        hostScope.cancel()
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun pngFileCapture_isNamedJpg() {
        val result = capture(Directory.DOCUMENTS)

        val path = assertIs<ImageCaptureResult.SuccessWithFile>(result).filePath
        assertTrue(path.endsWith(".jpg"), "a JPEG capture was named $path")
    }

    @Test
    fun pngGalleryCapture_isRegisteredAsJpeg() {
        capture(Directory.PICTURES)

        val values = mediaStore.rows.values.single()
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        val name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        assertTrue(name.endsWith(".jpg"), "a JPEG capture was named $name")
    }

    @Test
    fun pngConfiguration_isReportedAsAWarning() {
        recordWarnings()

        testController(context, backend, imageFormat = ImageFormat.PNG)

        assertTrue(warnings.any { "PNG" in it }, "no warning that PNG captures are JPEG on Android: $warnings")
    }

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
            CameraConfiguration(imageFormat = ImageFormat.PNG),
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

    private fun capture(directory: Directory): ImageCaptureResult {
        val controller = testController(context, backend, directory = directory, imageFormat = ImageFormat.PNG)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.last().deliverSaved(jpeg)
        idleMainLooper()
        assertTrue(result.isCompleted, "takePictureToFile did not return")
        return runBlocking { result.await() }
    }

    private fun recordWarnings() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
    }

    private companion object {
        val PNG_SIGNATURE = listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)

        // JPEG at quality 95 moves a flat colour a few levels; well inside a tenth of the range.
        const val COLOUR_TOLERANCE = 24
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
