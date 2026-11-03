@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * iOS 16 is CameraK's minimum, and a Kotlin/Native library cannot raise its consumer's deployment
 * target, so the engine checks the OS first. Below 16 it creates no session, enumerates nothing and
 * reaches no iOS 16 API: it is Failed(Unsupported(PLATFORM_VERSION)) and every call says so.
 */
class MinimumOsTest {
    private val driver = AppleEngineDriver(osVersion = 15)

    @Test
    fun belowIos16EngineReportsUnsupportedPlatformVersionAndCreatesNoSession() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()

        val failed = assertIs<EngineState.Failed>(engine.state.value, "the state of an engine below iOS 16")
        assertEquals(CameraFeature.PLATFORM_VERSION, assertIs<CameraKError.Unsupported>(failed.error).feature)
        assertEquals(0, driver.sessionsCreated, "sessions created below iOS 16")
        assertEquals(0, driver.enumerationsRun, "enumerations run below iOS 16")
        assertEquals(CameraCapabilities.EMPTY, engine.capabilities.value)
        engine.close()
    }

    @Test
    fun belowIos16EveryCallReturnsTheSameFailure() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val error = assertIs<EngineState.Failed>(engine.state.value).error
        val video = VideoConfiguration(enableAudio = false)

        val results = listOf(
            "start" to engine.start(backCamera()),
            "capture" to engine.capture(),
            "setZoom" to engine.setZoom(1f),
            "setFlashMode" to engine.setFlashMode(FlashMode.OFF),
            "setTorchMode" to engine.setTorchMode(TorchMode.OFF),
            "selectCamera" to engine.selectCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)),
            "setTargetOrientation" to engine.setTargetOrientation(null),
            "prepareRecording" to engine.prepareRecording(video),
            "startRecording" to engine.startRecording(video),
            "stopRecording" to engine.stopRecording(),
            "pauseRecording" to engine.pauseRecording(),
            "resumeRecording" to engine.resumeRecording(),
        )
        for ((call, result) in results) {
            assertSame(error, assertIs<CameraKResult.Failure>(result, call).error, "$call below iOS 16")
        }
        // Rule 1: stop from Failed publishes Closed, and the next start fails the same way again.
        engine.stop()
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value, "after stop")
        assertSame(error, assertIs<CameraKResult.Failure>(engine.start(backCamera()), "start after stop").error)
        assertSame(error, assertIs<EngineState.Failed>(engine.state.value, "after the second start").error)
        assertEquals(0, driver.sessionsCreated, "sessions created below iOS 16")
        engine.close()
    }

    @Test
    fun belowIos16CallsAfterCloseReturnClosed() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()

        engine.close()

        assertEquals(CameraKResult.Failure(CameraKError.Closed), engine.start(backCamera()), "start after close")
        assertEquals(CameraKResult.Failure(CameraKError.Closed), engine.capture(), "capture after close")
        assertEquals(EngineState.Closed, engine.state.value, "the state after close")
    }
}
