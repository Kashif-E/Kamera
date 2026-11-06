package com.kashif.analyzerplugin

import android.graphics.BitmapFactory
import android.graphics.Color
import com.kashif.cameraK.testing.TestFrame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Android encoder turns a common frame into a real JPEG through Bitmap, in its colours, scaled to maxDimension. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidFrameEncoderTest {
    @Test
    fun aYuvFrameIsEncodedAsAJpegScaledToMaxDimension() {
        val frame = TestFrame.luma(400, 200, ByteArray(400 * 200) { 0x60 }, rotationDegrees = 90, timestampNanos = 3L)

        val encoded = assertNotNull(BitmapFrameEncoder(AnalyzerConfig { maxDimension = 100 }).encode(frame))

        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size), "not a JPEG")
        assertEquals(100 to 50, bitmap.width to bitmap.height)
        assertEquals(100 to 50, encoded.width to encoded.height)
        assertEquals(90, encoded.rotationDegrees)
        assertEquals(3L, encoded.timestampNanos)
    }

    @Test
    fun anRgbaFrameKeepsItsColours() {
        val rgba = ByteArray(32 * 16 * 4)
        for (pixel in 0 until 32 * 16) {
            rgba[pixel * 4 + 2] = 0xE0.toByte()
            rgba[pixel * 4 + 3] = 0xFF.toByte()
        }

        val encoded = assertNotNull(BitmapFrameEncoder(AnalyzerConfig()).encode(TestFrame.rgba(32, 16, rgba)))

        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size))
        assertEquals(32 to 16, bitmap.width to bitmap.height)
        val pixel = bitmap.getPixel(16, 8)
        assertTrue(Color.blue(pixel) > 0xC0 && Color.red(pixel) < 0x40, "not the frame's blue: %08x".format(pixel))
    }
}
