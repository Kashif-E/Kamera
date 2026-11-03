@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.desktopEngine
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-device lock is keyed on the resolved device id, not on one default camera
 * (CameraGrabber.kt's `defaultCamera`): engines on two devices open at the same time, and a second
 * engine on a device waits until the first has released it (§2.4 desktop). The ids are new strings
 * per enumeration, as a real enumeration's are.
 */
class DeviceLockTest {
    @Test
    fun differentIdsOpenConcurrentlySameIdSerializes() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val opens = CopyOnWriteArrayList<Pair<String, ScriptedFrameGrabber>>()
        val devices = object : DesktopDevices {
            // StringBuilder: each enumeration's ids are new String instances.
            override fun list(): List<LensInfo> = listOf("usb-a", "usb-b").map { webcam(StringBuilder(it).toString()) }

            override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber =
                rig.newCamera().also { opens += id to it }
        }
        fun engine() =
            desktopEngine(CameraConfiguration(), CameraKPlatformContext(), rig.environment(), devices = devices)
        val onA = engine()
        val onB = engine()
        val secondOnA = engine()
        try {
            runCurrent()

            val a = async { onA.start(desiredOn("usb-a")) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), a.await())

            val b = async { onB.start(desiredOn("usb-b")) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), b.await(), "a second device opens while the first is open")

            val secondA = async { secondOnA.start(desiredOn("usb-a")) }
            rig.settle()
            assertEquals(EngineState.Opening, secondOnA.state.value, "a second engine opened a device that is in use")
            assertEquals(listOf("usb-a", "usb-b"), opens.map { it.first }, "devices opened")

            onA.stop()
            rig.settle()

            assertEquals(CameraKResult.Success(Unit), secondA.await(), "the device once its first engine released it")
            assertEquals(listOf("usb-a", "usb-b", "usb-a"), opens.map { it.first })
            assertEquals(1, opens[0].second.releaseCount, "the first engine's grabber before the second opened")
            assertEquals(EngineState.Open, onB.state.value)
        } finally {
            onA.close()
            onB.close()
            secondOnA.close()
            rig.settle()
        }
    }

    private fun desiredOn(id: String) = DesiredHardware(
        selection = CameraSelection.ById(id),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private fun webcam(id: String) =
        LensInfo(id, CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)
}
