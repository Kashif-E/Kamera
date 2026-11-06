@file:OptIn(com.kashif.cameraK.utils.CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.state.CameraConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flash and torch modes an engine is started with have to reach AVFoundation: FlashMode.OFF must
 * not fire the flash, and a torch must light once the session runs and again on whichever lens the
 * user switches to.
 */
class FlashTorchTest {
    private val back = FakeDevice(isFront = false)
    private val front = FakeDevice(isFront = true)

    private fun custom() = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { RecordingSession() },
        cameraDevices = { lens, _ ->
            when (lens) {
                CameraLens.FRONT -> front
                CameraLens.BACK, null -> back
            }
        },
    )

    private val scope = MainScope()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun startedEngine(flash: FlashMode, torch: TorchMode, custom: CustomCameraController): AppleCameraEngine {
        val engine =
            AppleCameraEngine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 }, CameraKEnvironment(), custom)
        val desired = DesiredHardware(backCamera().selection, flash, torch, 1f, null)
        scope.launch { engine.start(desired) }
        awaitTrue("the engine to report Open") { engine.state.value == EngineState.Open }
        return engine
    }

    @Test
    fun theFlashModeAStartAsksForIsWhatCapturesUse() {
        val custom = custom()
        val engine = startedEngine(FlashMode.OFF, TorchMode.OFF, custom)

        assertEquals(FlashMode.OFF, engine.hardware.value.flashMode, "a FlashMode.OFF start reports another mode")
        assertEquals(AVCaptureFlashModeOff, custom.flashMode, "a FlashMode.OFF start would still fire the flash")
        engine.close()
        drainSessionThenMain(custom)
    }

    @Test
    fun theTorchAStartAsksForLightsOnceTheSessionRunsAndFollowsALensSwitch() {
        val custom = custom()
        val engine = startedEngine(FlashMode.OFF, TorchMode.ON, custom)

        awaitTrue("the torch to reach the back camera") {
            back.callsOf("setTorchMode").any { it.value == AVCaptureTorchModeOn }
        }
        val applied = back.callsOf("setTorchMode").last()
        assertTrue(applied.locked, "the torch was set without the configuration lock: $applied")

        scope.launch { engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        awaitTrue("the torch to reach the front camera after the lens switch") {
            front.callsOf("setTorchMode").any { it.value == AVCaptureTorchModeOn }
        }
        engine.close()
        drainSessionThenMain(custom)
    }
}
