@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine traces a start and the time to its first frame as one interval each, opened when start
 * is called and closed when start returns and when the session runs; a start on an open engine
 * traces nothing more. The production Tracer turns these into os_signpost intervals.
 */
class AppleTracerTest {
    private val tracer = RecordingTracer()
    private val driver = AppleEngineDriver(tracer = tracer)

    @Test
    fun startAndFirstFrameOpenAndCloseOneInterval() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        driver.holdOpen()
        val start = async { engine.start(backCamera()) }
        driver.settle()

        assertEquals(1, tracer.count("begin ${TraceNames.START}"), "start intervals opened: ${tracer.events}")
        assertEquals(
            1,
            tracer.count("begin ${TraceNames.FIRST_FRAME}"),
            "first-frame intervals opened: ${tracer.events}",
        )
        assertEquals(0, tracer.count("end ${TraceNames.START}"), "the start interval closed before the platform opened")
        assertEquals(0, tracer.count("end ${TraceNames.FIRST_FRAME}"), "the first frame came before the session ran")

        driver.deliverOpen()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), start.await())
        assertEquals(1, tracer.count("end ${TraceNames.START}"), "start intervals closed: ${tracer.events}")
        assertEquals(1, tracer.count("end ${TraceNames.FIRST_FRAME}"), "first-frame intervals closed: ${tracer.events}")

        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(2, tracer.events.count { it.startsWith("begin") }, "a start on an open engine traced again")
        engine.close()
    }

    @Test
    fun theSignpostTracerOpensAndClosesIntervalsAndMarks() {
        val tracer = signpostTracer()
        tracer.beginInterval(TraceNames.START).close()
        tracer.beginInterval(TraceNames.FIRST_FRAME).use { tracer.marker(TraceNames.FIRST_FRAME) }
    }
}
