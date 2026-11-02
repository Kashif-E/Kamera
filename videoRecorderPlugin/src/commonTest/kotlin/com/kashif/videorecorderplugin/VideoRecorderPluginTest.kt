package com.kashif.videorecorderplugin

import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VideoRecorderPluginTest {
    @Test
    fun startRecording_withoutCamera_forwardsRecordingFailed_andDropsNonRecordingEvents() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
        val plugin = VideoRecorderPlugin()
        val received = mutableListOf<CameraKEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.recordingEvents.collect {
                received +=
                    it
            }
        }
        plugin.onAttach(holder)

        holder.emitEvent(CameraKEvent.QRCodeScanned("not a recording event"))
        plugin.startRecording()

        val failed = assertIs<CameraKEvent.RecordingFailed>(received.single(), "received: $received")
        assertEquals("Camera not initialized", failed.exception.message)

        plugin.onDetach()
        hostScope.cancel()
    }

    @Test
    fun detach_cancelsTheEventForwarder() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
        val plugin = VideoRecorderPlugin()
        val jobs = checkNotNull(holder.pluginScope.coroutineContext[Job])

        plugin.onAttach(holder)
        assertEquals(1, jobs.children.count { it.isActive }, "attach should start forwarding events")

        plugin.onDetach()
        assertEquals(0, jobs.children.count { it.isActive }, "onDetach must cancel the forwarder")

        hostScope.cancel()
    }

    @Test
    fun aSlowRecordingEventsCollector_doesNotHoldUpTheHoldersEventBus() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
        val plugin = VideoRecorderPlugin()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.recordingEvents.collect { awaitCancellation() }
        }
        plugin.onAttach(holder)

        // Every other events collector, and every capture, waits on emitEvent; it must not wait on
        // the app's recordingEvents collector.
        withTimeout(1_000) {
            repeat(3) { holder.emitEvent(CameraKEvent.RecordingFailed(Exception("recording failure $it"))) }
        }

        plugin.onDetach()
        hostScope.cancel()
    }
}
