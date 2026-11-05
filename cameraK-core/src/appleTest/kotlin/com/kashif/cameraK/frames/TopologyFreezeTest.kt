@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.controller.backCamera
import com.kashif.cameraK.controller.finishRecording
import com.kashif.cameraK.controller.removeFiles
import com.kashif.cameraK.controller.startEngineRecording
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.async
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS topology freeze (design §8, Decision 12): while a recording is starting, running or
 * finishing, the session's outputs do not change. A registration made then attaches only to a frame
 * output already present; otherwise it waits with `active` false, which its plugin shows as Paused,
 * and attaches once the recording has finalized. The frame output leaves the session only when
 * nothing records. With `frameAnalysis` the output is part of the startup transaction.
 */
class TopologyFreezeTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun registrationDuringRecordingWaitsAndPluginIsPaused() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertTrue(
            driver.session.videoDataOutputs.isEmpty(),
            "a frame output with no registration and no frameAnalysis",
        )
        val recording = startEngineRecording(driver, engine).also { written += it.path }
        val begun = driver.session.configurationsBegun

        val registration = engine.frames.register(FrameSpec()) { }
        driver.settle()
        assertNull(registration.rejection, "a YUV registration on a 420f session was rejected")
        assertFalse(registration.active.value, "a registration became active while the recording froze the outputs")
        assertTrue(driver.session.videoDataOutputs.isEmpty(), "the frame output was added during the recording")
        assertEquals(begun, driver.session.configurationsBegun, "a configuration was begun during the recording")

        val stop = async { engine.stopRecording() }
        driver.settle()
        finishRecording(driver, recording)
        repeat(20) { if (!stop.isCompleted) driver.settle() }
        driver.settle()
        assertEquals(1, driver.session.videoDataOutputs.size, "frame outputs once the recording finalized")
        assertTrue(registration.active.value, "the waiting registration once the recording finalized")
        registration.close()
        engine.close()
    }

    @Test
    fun outputNotRemovedWhileRecording() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val registration = engine.frames.register(FrameSpec()) { }
        driver.settle()
        assertTrue(registration.active.value, "a registration with the camera open and nothing recording")
        assertEquals(1, driver.session.videoDataOutputs.size, "frame outputs for one registration")
        val recording = startEngineRecording(driver, engine).also { written += it.path }

        registration.close()
        driver.settle()
        assertEquals(1, driver.session.videoDataOutputs.size, "the frame output was removed during the recording")

        val stop = async { engine.stopRecording() }
        driver.settle()
        finishRecording(driver, recording)
        repeat(20) { if (!stop.isCompleted) driver.settle() }
        driver.settle()
        assertTrue(
            driver.session.videoDataOutputs.isEmpty(),
            "the frame output stayed after the recording ended with no registration left",
        )
        engine.close()
    }

    @Test
    fun registrationAttachesDuringRecordingWhenOutputPresent() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val output = driver.session.videoDataOutputs.singleOrNull()
        assertTrue(output != null, "frameAnalysis started the camera without a frame output")
        assertEquals(1, driver.session.transactionOf(output), "the frame output joined the startup transaction")
        val recording = startEngineRecording(driver, engine).also { written += it.path }

        val frames = atomic(0)
        val registration = engine.frames.register(FrameSpec(synchronous = true)) { frames.incrementAndGet() }
        driver.settle()
        assertTrue(registration.active.value, "a registration during the recording, with the frame output present")
        assertTrue(driver.deliverFrame(), "no frame output took the sample buffer")
        assertEquals(1, frames.value, "frames the registration got during the recording")

        val stop = async { engine.stopRecording() }
        driver.settle()
        finishRecording(driver, recording)
        repeat(20) { if (!stop.isCompleted) driver.settle() }
        registration.close()
        engine.close()
    }
}
