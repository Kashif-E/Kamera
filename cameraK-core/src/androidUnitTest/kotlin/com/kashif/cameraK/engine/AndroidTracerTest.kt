@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.AndroidTracer
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The start interval runs from start until CameraX reports the camera open; the first-frame interval
 * from start until the preview's first frame, once per start.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidTracerTest {
    private val platform = FakeAndroidPlatform()
    private val tracer = RecordingTracer()

    @Test
    fun startAndFirstFrameEmitSections() = runTest {
        val engine = platform.engine(testScheduler, runtime = EngineRuntime(tracer = tracer))
        platform.backend.binding.holdNextOpen = true
        val start = async { engine.start(desired) }
        platform.settle(testScheduler)
        assertEquals(listOf(begin(START), begin(FIRST_FRAME)), tracer.events, "while CameraX opens the camera")

        platform.reportOpen()
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), start.await())
        assertEquals(listOf(begin(START), begin(FIRST_FRAME), end(START)), tracer.events, "once the camera is open")

        platform.backend.deliverPreviewFrame()
        platform.backend.deliverPreviewFrame()
        platform.settle(testScheduler)
        assertEquals(
            listOf(begin(START), begin(FIRST_FRAME), end(START), end(FIRST_FRAME)),
            tracer.events,
            "after the preview's frames",
        )
        engine.closeAndJoin()
    }

    @Test
    fun engineWithoutATestTracerUsesTheAndroidOne() = runTest {
        val engine = platform.engine(testScheduler)
        assertSame(AndroidTracer, engine.tracer)
        AndroidTracer.beginInterval(START).close()
        AndroidTracer.marker(START)
        engine.closeAndJoin()
    }

    private fun begin(name: String) = "begin $name"

    private fun end(name: String) = "end $name"

    private class RecordingTracer : Tracer {
        val events = mutableListOf<String>()

        override fun beginInterval(name: String): AutoCloseable {
            events += "begin $name"
            return AutoCloseable { events += "end $name" }
        }

        override fun marker(name: String) {
            events += "marker $name"
        }
    }

    private companion object {
        const val START = TraceNames.START
        const val FIRST_FRAME = TraceNames.FIRST_FRAME
    }
}
