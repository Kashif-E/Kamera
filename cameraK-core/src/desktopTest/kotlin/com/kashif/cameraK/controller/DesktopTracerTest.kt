@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The desktop engine marks the start of a session and its first frame through the runtime's
 * Tracer (§11.3): one marker each per start, however many frames follow, and however many times
 * the engine reopens the camera within that start.
 */
class DesktopTracerTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private class RecordingTracer : Tracer {
        val markers = CopyOnWriteArrayList<String>()

        override fun beginInterval(name: String): AutoCloseable = AutoCloseable { }

        override fun marker(name: String) {
            markers += name
        }
    }

    @Test
    fun startAndFirstFrameRecordOneMarkerEach() = runTest {
        val tracer = RecordingTracer()
        val rig = DesktopEngineRig(testScheduler, tracer = tracer)
        rig.camera.holdOpen = true
        val sut = rig.engine()
        try {
            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(listOf(TraceNames.START), tracer.markers.toList(), "before the first frame")

            repeat(FRAMES) {
                rig.camera.deliverFrame()
                rig.settle()
            }

            assertEquals(CameraKResult.Success(Unit), start.await())
            assertEquals(listOf(TraceNames.START, TraceNames.FIRST_FRAME), tracer.markers.toList())

            sut.stop()
            rig.camera.holdOpen = false
            val restart = async { sut.start(desired) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), restart.await())
            assertEquals(
                listOf(TraceNames.START, TraceNames.FIRST_FRAME, TraceNames.START, TraceNames.FIRST_FRAME),
                tracer.markers.toList(),
                "a second start",
            )
        } finally {
            sut.close()
        }
    }

    @Test
    fun firstFrameIsMarkedOncePerStartNotOnAReopen() = runTest {
        val tracer = RecordingTracer()
        val rig = DesktopEngineRig(testScheduler, tracer = tracer)
        val sut = rig.engine()
        try {
            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())

            // The camera stops delivering: the engine is Interrupted and reopens it, and the reopen
            // waits for the camera's next frame.
            rig.camera.holdOpen = true
            rig.camera.failGrab(IOException("the camera stopped delivering frames"))
            rig.settle()
            assertIs<EngineState.Interrupted>(sut.state.value, "after the camera stopped delivering")
            rig.camera.deliverFrame()
            rig.settle()
            assertEquals(EngineState.Open, sut.state.value, "after the reopened camera's first frame")

            assertEquals(
                listOf(TraceNames.START, TraceNames.FIRST_FRAME),
                tracer.markers.toList(),
                "a reopen within one start is not a new first frame",
            )
        } finally {
            sut.close()
        }
    }

    private companion object {
        const val FRAMES = 5
    }
}
