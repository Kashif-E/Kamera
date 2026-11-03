@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Flash and torch support come from the bound camera's CameraInfo.hasFlashUnit, the value CameraX
 * acts on; the enumeration's hasFlash is the first estimate until a bind reads it. Torch is OFF and
 * ON only: Android has no torch AUTO.
 */
@RunWith(RobolectricTestRunner::class)
class CapabilitiesFlashTorchTest {
    // The enumeration and CameraInfo disagree on both cameras.
    private val back = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 8f, false, false)
    private val front = LensInfo("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, 1f, 1f, true, false)
    private val platform = FakeAndroidPlatform(listOf(back, front), flashUnitOf = { !it.hasFlash })

    @Test
    fun flashFromHasFlashUnitAndTorchWithoutAuto() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(on(back)))
        platform.settle(testScheduler)

        val afterBack = engine.capabilities.value
        assertEquals(setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON), afterBack.supportedFlashModes(back.id))
        assertEquals(setOf(TorchMode.OFF, TorchMode.ON), afterBack.supportedTorchModes(back.id))
        assertEquals(true, engine.hardware.value.camera?.hasFlash, "the reported camera disagrees with its flash unit")

        engine.selectCamera(CameraSelection.ById(front.id))
        platform.settle(testScheduler)

        val afterFront = engine.capabilities.value
        assertEquals(setOf(FlashMode.OFF), afterFront.supportedFlashModes(front.id))
        assertEquals(setOf(TorchMode.OFF), afterFront.supportedTorchModes(front.id))
        assertEquals(setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON), afterFront.supportedFlashModes(back.id))
        for (lens in afterFront.allLenses) {
            assertFalse(TorchMode.AUTO in afterFront.supportedTorchModes(lens.id), "torch AUTO listed for '${lens.id}'")
        }
        engine.closeAndJoin()
    }

    private fun on(lens: LensInfo) = DesiredHardware(
        selection = CameraSelection.ById(lens.id),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )
}
