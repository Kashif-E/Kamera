@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import androidx.camera.core.ImageCapture
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.BACK_WITH_FLASH
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.FLASH_AND_ZOOM
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.FRONT
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.desired
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One desired flash, validated against each lens as it binds (design §6.2): a lens without a flash
 * unit binds FLASH_MODE_OFF and reports OFF, and the desired mode waits for the next lens that has
 * one, as 1.x bound the last requested mode once the lens had a flash again (w6 A2 carry-over 1 and 2).
 */
@RunWith(RobolectricTestRunner::class)
class FlashPerLensTest {
    private val platform = FakeAndroidPlatform(FLASH_AND_ZOOM)

    @Test
    fun aLensWithoutAFlashBindsFlashOffAndTheDesiredModeWaitsForTheNextLensWithOne() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(FlashMode.ON), engine.setFlashMode(FlashMode.ON), "setup: the back flash")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(FRONT.id)))
        platform.settle(testScheduler)
        assertEquals(ImageCapture.FLASH_MODE_OFF, boundCapture().flashMode, "the front camera has no flash unit")
        assertEquals(FlashMode.OFF, engine.hardware.value.flashMode, "the front camera reports its flash")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(BACK_WITH_FLASH.id)))
        platform.settle(testScheduler)
        assertEquals(ImageCapture.FLASH_MODE_ON, boundCapture().flashMode, "the desired flash on the back again")
        assertEquals(FlashMode.ON, engine.hardware.value.flashMode)
        engine.closeAndJoin()
    }

    private fun boundCapture(): ImageCapture = platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
}
