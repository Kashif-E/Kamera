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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/**
 * Live OCR on Android does not run ML Kit's text recognizer on every analysis frame: by default it
 * reads at most two frames a second, judged by frame timestamps, and closes the frames it skips
 * at once instead of after recognition.
 */
@RunWith(RobolectricTestRunner::class)
class LiveOcrRateTest {
    private val recognizer = CountingRecognizer()

    @Test
    fun byDefault_aThirtyFpsStream_isRecognisedTwiceASecond() {
        val analyzer = TextRecognitionAnalyzer(onTextRecognized = {}, onError = {}, recognizer = recognizer)
        val frames = List(30) { FakeFrame(timestampNanos = it * FRAME_NANOS) }

        frames.forEach(analyzer::analyze)
        val closedBeforeRecognitionFinished = frames.count { it.closes == 1 }
        shadowOf(Looper.getMainLooper()).idle()

        // Frames at 0 and 533 ms are read: the first ones at least 500 ms after the last read.
        assertEquals(2, recognizer.calls, "frames handed to ML Kit in one second of 30 fps frames")
        assertEquals(28, closedBeforeRecognitionFinished, "skipped frames closed straight away")
        assertEquals(List(30) { 1 }, frames.map { it.closes }, "close count per frame")
    }

    private class CountingRecognizer : LiveTextRecognizer {
        var calls = 0

        override fun recognize(frame: ImageProxy): Task<String> {
            calls++
            return Tasks.forResult("")
        }

        override fun close() = Unit
    }

    /** An analysis frame that only carries a timestamp and counts its closes. */
    private class FakeFrame(private val timestampNanos: Long) : ImageProxy {
        var closes = 0

        override fun close() {
            closes++
        }

        override fun getCropRect(): Rect = Rect(0, 0, SIDE, SIDE)

        override fun setCropRect(rect: Rect?) = Unit

        override fun getFormat(): Int = ImageFormat.YUV_420_888

        override fun getHeight(): Int = SIDE

        override fun getWidth(): Int = SIDE

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = timestampNanos

            override fun getRotationDegrees(): Int = 0

            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()

            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        override fun getImage(): Image? = null
    }

    private companion object {
        const val SIDE = 16
        const val FRAME_NANOS = 33_333_333L
    }
}
