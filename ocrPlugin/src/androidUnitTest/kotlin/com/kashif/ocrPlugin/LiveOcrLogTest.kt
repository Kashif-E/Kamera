package com.kashif.ocrPlugin

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.os.Looper
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.kashif.cameraK.utils.CameraKLogger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Live OCR logs what it did and how much it read, never the text: the log sink may be forwarded to
 * a crash reporter. A failure is logged once, with its throwable.
 */
@RunWith(RobolectricTestRunner::class)
class LiveOcrLogTest {
    private class Line(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?)

    private val lines = mutableListOf<Line>()

    @Before
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            synchronized(lines) { lines += Line(level, message, throwable) }
        }
    }

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun recognisedText_isNotLogged() {
        val received = mutableListOf<String>()
        val recognizer = Recognizer(Tasks.forResult(SECRET))
        val analyzer = TextRecognitionAnalyzer(onTextRecognized = { received += it }, recognizer = recognizer)

        analyzer.analyze(Frame())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(SECRET), received, "the text still reaches the plugin")
        assertTrue(lines.isNotEmpty(), "live OCR logged nothing, so the check below proves nothing")
        val messages = lines.map { it.message }
        assertTrue(messages.none { SECRET_PART in it }, "log lines carrying the text: $messages")
    }

    @Test
    fun aRecognitionFailure_isLoggedOnceWithItsThrowable() {
        val failure = IllegalStateException("model not downloaded")
        // The error handler live OCR installs by default, as startRecognition does.
        val recognizer = Recognizer(Tasks.forException(failure))
        val analyzer = TextRecognitionAnalyzer(onTextRecognized = {}, recognizer = recognizer)

        analyzer.analyze(Frame())
        shadowOf(Looper.getMainLooper()).idle()

        val errors = lines.filter { it.level == CameraKLogger.Level.ERROR }
        assertEquals(1, errors.size, "error lines for one failure: ${errors.map { it.message }}")
        assertSame(failure, errors.single().throwable)
    }

    private class Recognizer(private val result: Task<String>) : LiveTextRecognizer {
        override fun recognize(frame: ImageProxy): Task<String> = result

        override fun close() = Unit
    }

    private class Frame : ImageProxy {
        override fun close() = Unit

        override fun getCropRect(): Rect = Rect(0, 0, SIDE, SIDE)

        override fun setCropRect(rect: Rect?) = Unit

        override fun getFormat(): Int = ImageFormat.YUV_420_888

        override fun getHeight(): Int = SIDE

        override fun getWidth(): Int = SIDE

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = 0L

            override fun getRotationDegrees(): Int = 0

            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()

            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        override fun getImage(): Image? = null
    }

    private companion object {
        const val SIDE = 16
        const val SECRET = "IBAN DE89 3704 0044 0532 0130 00"
        const val SECRET_PART = "0532 0130"
    }
}
