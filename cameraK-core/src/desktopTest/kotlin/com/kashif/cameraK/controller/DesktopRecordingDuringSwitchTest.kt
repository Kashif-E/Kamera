@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A camera switch during a recording is refused with `Busy` and changes nothing (Decision 7): the
 * recording goes on, on the same device, and no grab loop starts for another one. `Busy(LENS)`,
 * or `Busy(DEVICE_TYPE)` when the switch keeps the lens, as the holder's `switchOperation` decides. The 1.x
 * facade's toggle, which goes through `engine.selectCamera`, logs the refusal once at warning.
 * The desktop twin of Android's `SelectCameraTest.selectDuringRecordingIsBusyAndNothingRebinds`.
 */
class DesktopRecordingDuringSwitchTest {
    private val back = webcam("usb-back", CameraLens.BACK, CameraDeviceType.DEFAULT)
    private val front = webcam("usb-front", CameraLens.FRONT, CameraDeviceType.DEFAULT)
    private val wide = webcam("usb-wide", CameraLens.BACK, CameraDeviceType.WIDE_ANGLE)
    private val warnings = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun selectCameraDuringARecordingIsBusyLensAndNoRunStarts() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(enumerate = { CameraCapabilities(listOf(back, front, wide)) })
        try {
            steps.open(sut, DesktopRecordingSteps.desiredOn(CameraSelection.ById(back.id)))
            steps.at(1_000)
            steps.startRecording(sut)
            steps.frameAt(1_040)

            assertBusy(CameraOperation.LENS, sut.selectCamera(CameraSelection.ById(front.id)), "to the front device")
            assertBusy(
                CameraOperation.DEVICE_TYPE,
                sut.selectCamera(CameraSelection.ById(wide.id)),
                "to the wide device",
            )
            assertBusy(
                CameraOperation.LENS,
                sut.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)),
                "to the front lens",
            )
            rig.settle()

            assertEquals(1, rig.camera.startCount, "a grab loop started for another device")
            assertEquals(back, sut.hardware.value.camera, "the camera during the recording")
            steps.frameAt(1_080)
            assertEquals(2, rig.recorders.single().recordCount.get(), "the recording stopped taking frames")
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
        } finally {
            sut.close()
            rig.settle()
        }
    }

    // The holder's switchOperation: a switch that keeps the lens is DEVICE_TYPE, even between two
    // webcams of the same lens and device type (w7 decisions, D3' carry-over 1).
    @Test
    fun aSecondWebcamOfTheSameLensAndTypeIsBusyDeviceType() = runTest {
        val other = webcam("usb-other", CameraLens.BACK, CameraDeviceType.DEFAULT)
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(enumerate = { CameraCapabilities(listOf(back, other)) })
        try {
            steps.open(sut, DesktopRecordingSteps.desiredOn(CameraSelection.ById(back.id)))
            steps.at(1_000)
            steps.startRecording(sut)
            steps.frameAt(1_040)

            assertBusy(
                CameraOperation.DEVICE_TYPE,
                sut.selectCamera(CameraSelection.ById(other.id)),
                "to another BACK/DEFAULT webcam",
            )
            rig.settle()

            assertEquals(1, rig.camera.startCount, "a grab loop started for another device")
            assertEquals(back, sut.hardware.value.camera, "the camera during the recording")
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun toggleCameraLensDuringAnEngineRecordingKeepsTheRecordingAndTheDevice() = runTest {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ ->
            if (level == CameraKLogger.Level.WARN && "Busy" in message) warnings += message
        }
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(enumerate = { CameraCapabilities(listOf(back, front)) })
        val facade = sut.legacyController
        try {
            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut)
            steps.frameAt(1_040)

            facade.toggleCameraLens()
            rig.settle()

            assertEquals(1, warnings.size, "the refused toggle's warnings: $warnings")
            assertEquals(1, rig.camera.startCount, "the toggle started a grab loop for another device")
            assertEquals(back, sut.hardware.value.camera, "the camera after a toggle during a recording")
            steps.frameAt(1_080)
            assertEquals(2, rig.recorders.single().recordCount.get(), "the recording stopped taking frames")
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    private fun assertBusy(operation: CameraOperation, result: CameraKResult<LensInfo>, switch: String) {
        val busy = assertIs<CameraKError.Busy>(assertIs<CameraKResult.Failure>(result, "switch $switch").error)
        assertEquals(operation, busy.operation, "switch $switch")
    }

    private fun webcam(id: String, lens: CameraLens, type: CameraDeviceType) =
        LensInfo(id, type, lens, 1f, 1f, hasFlash = false, isLogical = false)
}
