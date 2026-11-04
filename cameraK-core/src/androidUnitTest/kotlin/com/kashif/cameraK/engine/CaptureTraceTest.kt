@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A capture is one `CameraK.capture` interval, from the request until CameraX reports the saved file. */
@RunWith(RobolectricTestRunner::class)
class CaptureTraceTest {
    private val platform = FakeAndroidPlatform()
    private val events = mutableListOf<String>()
    private val tracer = object : Tracer {
        override fun beginInterval(name: String): AutoCloseable {
            events += "begin $name"
            return AutoCloseable { events += "end $name" }
        }

        override fun marker(name: String) {
            events += "marker $name"
        }
    }

    @Test
    fun captureEmitsSection() = runTest {
        val engine = platform.engine(testScheduler, runtime = EngineRuntime(tracer = tracer))
        open(platform, engine)
        events.clear()

        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        assertEquals(listOf("begin ${TraceNames.CAPTURE}"), events, "before CameraX saved the still")
        platform.backend.captures.single().deliverSaved(byteArrayOf(1))
        platform.settle(testScheduler)

        assertIs<CameraKResult.Success<*>>(capture.await())
        assertEquals(listOf("begin ${TraceNames.CAPTURE}", "end ${TraceNames.CAPTURE}"), events)
        engine.closeAndJoin()
    }
}
