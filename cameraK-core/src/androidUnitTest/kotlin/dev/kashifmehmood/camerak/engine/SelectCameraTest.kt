@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.BACK
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.FRONT
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.desired
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.CameraOperation
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * selectCamera decides NoCamera from the enumerated cameras, before anything binds, and refuses a
 * switch during a recording with Busy(LENS), leaving the bound camera as it was.
 */
@RunWith(RobolectricTestRunner::class)
class SelectCameraTest {
    // Characterization: A1''s CameraSelectorResolver already decides this before binding.
    @Test
    fun noCameraDecidedBeforeBinding() = runTest {
        val platform = FakeAndroidPlatform(listOf(BACK))
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        val front = assertIs<CameraKResult.Failure>(
            engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)),
            "a front camera on a device with none",
        )
        assertEquals(CameraLens.FRONT, assertIs<CameraKError.NoCamera>(front.error).lens)
        val unknown = assertIs<CameraKResult.Failure>(engine.selectCamera(CameraSelection.ById("9")), "an unknown id")
        assertNull(assertIs<CameraKError.NoCamera>(unknown.error).lens)
        platform.settle(testScheduler)

        assertEquals(1, platform.backend.binding.bindCalls, "a camera that does not exist was bound")
        assertEquals(EngineState.Open, engine.state.value, "a refused switch ended the session")
        assertEquals(BACK.id, engine.hardware.value.camera?.id)
        engine.closeAndJoin()
    }

    // A configuration with no lens starts on Default, which opens what ByLens(BACK) opens (§6.1 "Default lens").
    @Test
    fun defaultSelectionOpensTheBackCamera() = runTest {
        val platform = FakeAndroidPlatform(listOf(FRONT, BACK))
        val engine = platform.engine(testScheduler)

        val start = engine.start(
            DesiredHardware(
                selection = CameraSelection.Default(CameraDeviceType.DEFAULT),
                flashMode = desired.flashMode,
                torchMode = desired.torchMode,
                zoomRatio = desired.zoomRatio,
                targetOrientation = null,
            ),
        )
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), start, "a start on the default camera")
        assertEquals(BACK.id, engine.hardware.value.camera?.id, "the default camera is not the back one")
        engine.closeAndJoin()
    }

    // Characterization: the engine's selectCamera has returned Busy(LENS) while recording since A1'
    // (EngineSession.kt, the videoRecorder.isRecording branch).
    @Test
    fun selectDuringRecordingIsBusyAndNothingRebinds() = runTest {
        val platform = FakeAndroidPlatform(listOf(BACK, FRONT))
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val recording = async { engine.startRecording(VideoConfiguration { enableAudio = false }) }
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<String>>(recording.await(), "setup: the recording")
        val binds = platform.backend.binding.bindCalls

        val busy = assertIs<CameraKResult.Failure>(engine.selectCamera(CameraSelection.ById(FRONT.id)))
        assertEquals(CameraOperation.LENS, assertIs<CameraKError.Busy>(busy.error).operation)
        platform.settle(testScheduler)

        assertEquals(binds, platform.backend.binding.bindCalls, "a switch during a recording rebound the camera")
        assertEquals(BACK.id, engine.hardware.value.camera?.id)
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }
}
