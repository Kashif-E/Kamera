@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.engine.desktopEngine
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * w9 carry-over 5 (W4). The 1.x desktop constructors keyed their device lock on one default camera
 * and listed only it, so a 1.x controller and a bridge engine on the same device did not exclude
 * each other. The 2.0 engine lists every enumerated device and locks each by its real id: a second
 * engine on the third device waits for the first to release it.
 */
class DesktopDeviceListTest {
    @Test
    fun everyEnumeratedDeviceIsListedAndLockedByItsId() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val ids = listOf("usb-1", "usb-2", "usb-3")
        val opens = CopyOnWriteArrayList<String>()
        val devices = object : DesktopDevices {
            override fun list(): List<LensInfo> = ids.map { webcam(StringBuilder(it).toString()) }

            override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = rig.newCamera().also { opens += id }
        }
        fun engine() =
            desktopEngine(CameraConfiguration(), CameraKPlatformContext(), rig.environment(), devices = devices)
        val first = engine()
        val second = engine()
        try {
            runCurrent()
            rig.settle()
            assertEquals(ids, first.capabilities.value.allLenses.map { it.id }, "the devices the engine lists")

            val opened = async { first.start(desiredOn("usb-3")) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), opened.await())

            val waiting = async { second.start(desiredOn("usb-3")) }
            rig.settle()
            assertEquals(EngineState.Opening, second.state.value, "a second engine opened usb-3 while it was in use")
            assertEquals(listOf("usb-3"), opens.toList(), "devices opened")

            val stop = async { first.stop() }
            rig.settle()
            stop.await()
            assertEquals(CameraKResult.Success(Unit), waiting.await(), "usb-3 once the first engine released it")
            assertEquals(listOf("usb-3", "usb-3"), opens.toList())
        } finally {
            first.close()
            second.close()
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
