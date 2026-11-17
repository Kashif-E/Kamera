@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.engine.EngineRecordingEvent
import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * The steps the desktop recording tests share, over a [DesktopEngineRig] on the case's scheduler:
 * open the engine, start a recording, let the camera deliver a frame at a time on the rig's clock.
 */
internal class DesktopRecordingSteps(private val scope: TestScope, val rig: DesktopEngineRig) {
    val desired = desiredOn(CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT))

    /** Every recording event the engine emits from now on, in order. */
    fun events(sut: DesktopCameraEngine): List<EngineRecordingEvent> {
        val events = CopyOnWriteArrayList<EngineRecordingEvent>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            sut.recordingEvents.collect { events += it }
        }
        return events
    }

    suspend fun open(sut: DesktopCameraEngine, desired: DesiredHardware = this.desired) {
        val start = scope.async { sut.start(desired) }
        rig.settle()
        assertEquals(CameraKResult.Success(Unit), start.await(), "start")
        assertEquals(EngineState.Open, sut.state.value)
    }

    suspend fun startRecording(sut: DesktopCameraEngine, config: VideoConfiguration = video()): String {
        val start = scope.async { sut.startRecording(config) }
        rig.settle()
        return assertIs<CameraKResult.Success<String>>(start.await(), "startRecording").value
    }

    suspend fun stopRecording(sut: DesktopCameraEngine): CameraKResult<RecordedVideo> {
        val stop = scope.async { sut.stopRecording() }
        rig.settle()
        return stop.await()
    }

    /** The camera delivers one frame, captured at [millis] on the rig's clock. */
    fun frameAt(millis: Long) {
        rig.clock.set(millis * NANOS_PER_MILLI)
        rig.camera.deliverFrame()
        rig.settle()
    }

    /** The rig's clock reads [millis] from now on. */
    fun at(millis: Long) = rig.clock.set(millis * NANOS_PER_MILLI)

    fun video(maxDurationMillis: Long? = null, maxFileSizeBytes: Long? = null, outputDirectory: String? = null) =
        VideoConfiguration {
            enableAudio = false
            maxDuration = maxDurationMillis?.milliseconds
            this.maxFileSizeBytes = maxFileSizeBytes
            this.outputDirectory = outputDirectory
        }

    companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        fun desiredOn(selection: CameraSelection) = DesiredHardware(
            selection = selection,
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            zoomRatio = 1f,
            targetOrientation = null,
        )
    }
}
