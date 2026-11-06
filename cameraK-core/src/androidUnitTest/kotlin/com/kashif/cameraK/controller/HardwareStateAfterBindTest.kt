@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every bind gives CameraX a camera with the torch off and no zoom. Torch and zoom are what the user
 * (or the configuration) asked for, so they are applied to each camera a bind returns: the first one,
 * and every one after a lens switch, a device-type change or a recording start or stop.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareStateAfterBindTest {
    private val problems = mutableListOf<String>()

    @After
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun torchAndZoomSetByTheUser_areAppliedAgainAfterARebind() = runTest {
        val platform = FakeAndroidPlatform(FakeAndroidPlatform.CONTRACT_CAMERAS)
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        assertIs<CameraKResult.Success<*>>(engine.setZoom(2.5f))
        platform.settle(testScheduler)

        val switch = async { engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(switch.await(), "the lens switch")

        val rebound = platform.backend.binding.cameras.last()
        assertEquals(2, platform.backend.binding.cameras.size, "setup: the lens switch should rebind")
        assertEquals(listOf(true), rebound.torchRequests, "the torch went dark after the rebind")
        assertEquals(listOf(2.5f), rebound.zoomRequests, "the zoom fell back to 1x after the rebind")
        engine.closeAndJoin()
    }

    @Test
    fun torchOnACameraWithoutAFlashUnit_isNotRequestedAndIsReported() = runTest {
        val platform = FakeAndroidPlatform(FakeAndroidPlatform.CONTRACT_CAMERAS, flashUnitOf = { false })
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        val result = engine.setTorchMode(TorchMode.ON)
        platform.settle(testScheduler)

        assertEquals(
            emptyList(),
            platform.backend.binding.cameras.single().torchRequests,
            "the torch was requested from a camera with no flash unit",
        )
        val refused = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraFeature.TORCH, refused.feature, "the report that the camera has no flash unit")
        engine.closeAndJoin()
    }

    @Test
    fun torchRequestThatCameraXFails_isReported() = runTest {
        val platform = FakeAndroidPlatform(FakeAndroidPlatform.CONTRACT_CAMERAS)
        platform.backend.binding.cameraFor = { FakeCamera(torchFailure = IllegalStateException("Camera is closed")) }
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        recordProblems()

        engine.setTorchMode(TorchMode.ON)
        platform.settle(testScheduler)
        engine.setTorchMode(TorchMode.OFF)
        platform.settle(testScheduler)

        val failures = problems.filter { "torch" in it.lowercase() && "failed" in it }
        assertEquals(2, failures.size, "a failed torch request was dropped: $problems")
        engine.closeAndJoin()
    }

    private fun recordProblems() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level != CameraKLogger.Level.DEBUG) problems += message }
    }
}
