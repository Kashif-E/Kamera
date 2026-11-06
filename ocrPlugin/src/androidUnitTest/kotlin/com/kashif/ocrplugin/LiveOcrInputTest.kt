package com.kashif.ocrPlugin

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.copyLuma
import com.kashif.cameraK.testing.TestFrame
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * ML Kit reads live frames as NV21 built from the frame's luma with neutral chroma, turned by the
 * frame's rotation, in either analysis format, and its client is closed when the session ends.
 */
@RunWith(RobolectricTestRunner::class)
class LiveOcrInputTest {
    @Test
    fun aYuvFrameReachesMlKitAsItsLumaWithNeutralChromaAndItsRotation() = runTest {
        val client = FakeMlKitClient { textTask("TEXT") }
        val session = ocrSession(OcrPlugin(OcrConfig(), AndroidTextRecognizers(newClient = { client })))
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(width = 4, height = 2, rotationDegrees = 270))

        val seen = client.nv21.single()
        assertEquals(Triple(4, 2, 270), Triple(seen.width, seen.height, seen.rotationDegrees))
        assertEquals(List(8) { it.toByte() } + List(4) { 128.toByte() }, seen.bytes, "NV21 bytes")
    }

    @Test
    fun anRgbaFrameReachesMlKitAsItsLuma() = runTest {
        val client = FakeMlKitClient { textTask("TEXT") }
        val session =
            ocrSession(OcrPlugin(OcrConfig(), AndroidTextRecognizers(newClient = { client })), FrameFormat.RGBA_8888)
        startReady(session)
        val frame = TestFrame.rgba(2, 2, ByteArray(16) { (it * 13).toByte() })
        val luma = ByteArray(4).also { frame.copyLuma(it) }.toList()

        session.engine.fakeFrames.emit(frame)

        assertEquals(luma + List(2) { 128.toByte() }, client.nv21.single().bytes, "NV21 bytes")
    }

    @Test
    fun theLiveClientIsClosedOnceWhenTheSessionEnds() = runTest {
        val client = FakeMlKitClient { textTask("TEXT") }
        val plugin = OcrPlugin(OcrConfig(), AndroidTextRecognizers(newClient = { client }))
        val session = ocrSession(plugin)
        startReady(session)

        plugin.pause()
        testScheduler.runCurrent()

        assertEquals(1, client.closes.get(), "close count")
    }
}
