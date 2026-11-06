@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.BACK
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FRONT
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A USB camera the enumeration listed may be gone by the time it is selected, so CameraX no longer
 * offers its id. Its selection falls back to the back camera, with a warning. On a device with no
 * back camera nothing can stand in: that is `NoCamera`, decided before anything unbinds, so the
 * current camera stays, as for any other camera that does not exist.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalCameraFallbackTest {
    private val warnings = mutableListOf<String>()

    @Before
    fun captureWarnings() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
    }

    @After
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun anExternalCameraCameraXDoesNotOffer_opensTheBackCameraWithAWarning() = runTest {
        val platform = FakeAndroidPlatform(listOf(BACK, FRONT, EXTERNAL), offeredByCameraX = listOf(BACK, FRONT))
        val engine = startOnTheFrontCamera(platform)

        val switch = async { engine.selectCamera(CameraSelection.ById(EXTERNAL.id)) }
        platform.settle(testScheduler)

        assertEquals(BACK.id, assertIs<CameraKResult.Success<LensInfo>>(switch.await()).value.id)
        assertEquals(BACK.id, engine.hardware.value.camera?.id, "the camera that stood in is not the back one")
        assertTrue(
            warnings.any { EXTERNAL.id in it && "back camera" in it },
            "no warning said the back camera stands in for the external one: $warnings",
        )
        engine.closeAndJoin()
    }

    @Test
    fun anExternalCameraCameraXDoesNotOffer_withNoBackCamera_isNoCameraAndTheCurrentCameraStays() = runTest {
        val platform = FakeAndroidPlatform(listOf(FRONT, EXTERNAL), offeredByCameraX = listOf(FRONT))
        val engine = startOnTheFrontCamera(platform)
        val binds = platform.backend.binding.bindCalls

        val switch = async { engine.selectCamera(CameraSelection.ById(EXTERNAL.id)) }
        platform.settle(testScheduler)

        assertNoCameraAndTheFrontCameraStays(platform, engine, binds, switch.await())
        engine.closeAndJoin()
    }

    // A prepared VideoCapture is in every bind, and a refused bind with it reads as a stream
    // combination CameraX cannot run; no camera to select is still NoCamera.
    @Test
    fun anExternalCameraCameraXDoesNotOffer_withNoBackCameraAndVideoPrepared_isNoCamera() = runTest {
        val platform = FakeAndroidPlatform(listOf(FRONT, EXTERNAL), offeredByCameraX = listOf(FRONT))
        val engine = startOnTheFrontCamera(platform)
        assertEquals(CameraKResult.Success(Unit), engine.prepareRecording(VideoConfiguration()), "setup: prepare")
        platform.settle(testScheduler)
        val binds = platform.backend.binding.bindCalls

        val switch = async { engine.selectCamera(CameraSelection.ById(EXTERNAL.id)) }
        platform.settle(testScheduler)

        assertNoCameraAndTheFrontCameraStays(platform, engine, binds, switch.await())
        engine.closeAndJoin()
    }

    // Characterization: a start has nothing bound to keep, and CameraX's refusal was already NoCamera.
    @Test
    fun aStartOnAnExternalCameraCameraXDoesNotOffer_withNoBackCamera_isNoCamera() = runTest {
        val platform = FakeAndroidPlatform(listOf(FRONT, EXTERNAL), offeredByCameraX = listOf(FRONT))
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)

        val start = async { engine.start(desiredOn(CameraSelection.ById(EXTERNAL.id))) }
        platform.settle(testScheduler)

        assertIs<CameraKError.NoCamera>(assertIs<CameraKResult.Failure>(start.await()).error)
        assertEquals(0, platform.backend.binding.bindCalls, "a camera CameraX does not have was bound")
        engine.closeAndJoin()
    }

    private suspend fun TestScope.startOnTheFrontCamera(platform: FakeAndroidPlatform): AndroidCameraEngine {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        val front = desiredOn(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT))
        assertEquals(CameraKResult.Success(Unit), engine.start(front), "setup: start")
        platform.settle(testScheduler)
        assertEquals(EngineState.Open, engine.state.value, "setup: the front camera opened")
        return engine
    }

    private fun assertNoCameraAndTheFrontCameraStays(
        platform: FakeAndroidPlatform,
        engine: AndroidCameraEngine,
        binds: Int,
        result: CameraKResult<LensInfo>,
    ) {
        val failure = assertIs<CameraKResult.Failure>(result, "selecting a camera nothing can stand in for")
        assertIs<CameraKError.NoCamera>(failure.error, "the error for a camera nothing can stand in for")
        assertEquals(EngineState.Open, engine.state.value, "the refused switch ended the session")
        assertEquals(binds, platform.backend.binding.bindCalls, "the refused switch rebound the camera")
        assertEquals(FRONT.id, engine.hardware.value.camera?.id, "the front camera did not stay")
    }

    private fun desiredOn(selection: CameraSelection) = DesiredHardware(
        selection = selection,
        flashMode = desired.flashMode,
        torchMode = desired.torchMode,
        zoomRatio = desired.zoomRatio,
        targetOrientation = null,
    )

    private companion object {
        // CameraX reports a USB camera's facing as EXTERNAL; the enumeration files it under BACK.
        val EXTERNAL = LensInfo("5", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 1f, false, false, true)
    }
}
