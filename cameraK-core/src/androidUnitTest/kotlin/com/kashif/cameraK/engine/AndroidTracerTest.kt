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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTrace
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The start interval runs from start until CameraX reports the camera open; the first-frame interval
 * from start until the preview's first frame, once per start. The Android tracer's sections reach
 * androidx.tracing, which writes them to android.os.Trace (shadowed by Robolectric from API 29, where
 * async sections exist).
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

    @Config(sdk = [35])
    @Test
    fun intervalsAndMarkersReachAndroidxTracingWithMatchingNamesAndCookies() {
        ShadowTrace.reset()

        val first = AndroidTracer.beginInterval(START)
        val second = AndroidTracer.beginInterval(FIRST_FRAME)
        val open = ShadowTrace.getCurrentAsyncSections().map { it.sectionName to it.cookie }
        assertEquals(setOf(START, FIRST_FRAME), open.map { it.first }.toSet(), "the async sections begun")
        assertEquals(2, open.map { it.second }.toSet().size, "two open intervals share a cookie: $open")

        second.close()
        first.close()
        assertTrue(ShadowTrace.getCurrentAsyncSections().isEmpty(), "an interval was left open")
        assertEquals(
            open.toSet(),
            ShadowTrace.getPreviousAsyncSections().map { it.sectionName to it.cookie }.toSet(),
            "each interval ended under the name and cookie it began with",
        )

        AndroidTracer.marker(TraceNames.LENS_SWITCH)
        assertTrue(ShadowTrace.getCurrentSections().isEmpty(), "the marker's section was left open")
        assertEquals(listOf(TraceNames.LENS_SWITCH), ShadowTrace.getPreviousSections().toList())
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
