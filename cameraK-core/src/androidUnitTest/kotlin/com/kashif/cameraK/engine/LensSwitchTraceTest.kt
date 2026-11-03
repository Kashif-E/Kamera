@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FRONT
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A camera switch is one `CameraK.lensSwitch` interval, from selectCamera until the new camera is
 * bound; a switch that fails ends its interval too, so none is left open.
 */
@RunWith(RobolectricTestRunner::class)
class LensSwitchTraceTest {
    private val platform = FakeAndroidPlatform()
    private val tracer = RecordingTracer()

    @Test
    fun switchEmitsSection() = runTest {
        val engine = platform.engine(testScheduler, runtime = EngineRuntime(tracer = tracer))
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        tracer.events.clear()

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(FRONT.id)))
        assertEquals(listOf("begin $LENS_SWITCH", "end $LENS_SWITCH"), tracer.events, "a switch that succeeded")

        tracer.events.clear()
        assertIs<CameraKResult.Failure>(engine.selectCamera(CameraSelection.ById("9")))
        assertEquals(listOf("begin $LENS_SWITCH", "end $LENS_SWITCH"), tracer.events, "a switch that failed")
        engine.closeAndJoin()
    }

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
        const val LENS_SWITCH = TraceNames.LENS_SWITCH
    }
}
