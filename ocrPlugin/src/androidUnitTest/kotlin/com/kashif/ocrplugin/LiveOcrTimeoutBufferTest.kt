package com.kashif.ocrplugin

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ML Kit reads the NV21 array it was handed while its task runs. A recognition that times out
 * leaves that task running, so the next frame is written into a new array, never the one ML Kit
 * may still be reading.
 */
@RunWith(RobolectricTestRunner::class)
class LiveOcrTimeoutBufferTest {
    @Test
    fun aFrameAfterATimeoutIsNotWrittenIntoTheArrayMlKitMayStillRead() {
        val handed = mutableListOf<ByteArray>()
        val answers = ArrayDeque(listOf(TaskCompletionSource<List<FoundBlock>>().task, textTask("TEXT")))
        val client = object : MlKitTextClient {
            override fun recognize(bitmap: Bitmap, rotationDegrees: Int): Task<List<FoundBlock>> =
                error("live recognition reads NV21")

            override fun recognizeNv21(
                nv21: ByteArray,
                width: Int,
                height: Int,
                rotationDegrees: Int,
            ): Task<List<FoundBlock>> {
                handed += nv21
                return answers.removeFirst()
            }

            override fun close() = Unit
        }
        val recognizer = AndroidTextRecognizers(newClient = { client }, liveTimeoutMillis = 1).live(OcrLevel.FAST)

        assertFailsWith<TimeoutException> { recognizer.recognize(LumaImage(ByteArray(4) { 1 }, 2, 2, 0)) }
        recognizer.recognize(LumaImage(ByteArray(4) { 2 }, 2, 2, 0))

        assertEquals(List(4) { 1.toByte() }, handed.first().take(4), "the luma of the frame ML Kit was still reading")
    }

    @Test
    fun aFrameAfterAnInterruptedWaitIsNotWrittenIntoTheArrayMlKitMayStillRead() {
        val handed = mutableListOf<ByteArray>()
        val answers = ArrayDeque(listOf(TaskCompletionSource<List<FoundBlock>>().task, textTask("TEXT")))
        val client = object : MlKitTextClient {
            override fun recognize(bitmap: Bitmap, rotationDegrees: Int): Task<List<FoundBlock>> =
                error("live recognition reads NV21")

            override fun recognizeNv21(
                nv21: ByteArray,
                width: Int,
                height: Int,
                rotationDegrees: Int,
            ): Task<List<FoundBlock>> {
                handed += nv21
                return answers.removeFirst()
            }

            override fun close() = Unit
        }
        val recognizer = AndroidTextRecognizers(newClient = { client }).live(OcrLevel.FAST)

        Thread.currentThread().interrupt()
        assertFailsWith<InterruptedException> { recognizer.recognize(LumaImage(ByteArray(4) { 1 }, 2, 2, 0)) }
        recognizer.recognize(LumaImage(ByteArray(4) { 2 }, 2, 2, 0))

        assertEquals(List(4) { 1.toByte() }, handed.first().take(4), "the luma of the frame ML Kit was still reading")
    }
}
