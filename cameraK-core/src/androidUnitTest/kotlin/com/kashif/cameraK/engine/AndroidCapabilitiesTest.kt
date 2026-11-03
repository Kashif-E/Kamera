@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine enumerates its cameras on its own scope after construction, never in the constructor,
 * and reports the first real values of §6.3: flash and torch from each lens's flash unit, and pause
 * and resume, which CameraX's Recorder supports.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidCapabilitiesTest {
    private val withFlash = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 8f, true, false)
    private val withoutFlash = LensInfo("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, 1f, 1f, false, false)
    private val platform = FakeAndroidPlatform(listOf(withFlash, withoutFlash))

    @Test
    fun enumeratedOnTheEngineScopeNotInTheConstructor() = runTest {
        val engine = platform.engine(testScheduler)
        assertTrue(platform.enumerations.isEmpty(), "the constructor enumerated the cameras")
        assertEquals(CameraCapabilities.EMPTY, engine.capabilities.value, "before enumeration")

        platform.settle(testScheduler)
        assertEquals(1, platform.enumerations.size, "enumerations after the engine's scope ran")
        assertEquals(listOf(withFlash, withoutFlash), engine.capabilities.value.allLenses)
        engine.closeAndJoin()
    }

    @Test
    fun pauseResumeSupported() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertTrue(engine.capabilities.value.supportsPauseResume)
        engine.closeAndJoin()
    }

    @Test
    fun flashModesFromHasFlash() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        val capabilities = engine.capabilities.value

        assertEquals(setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON), capabilities.supportedFlashModes(withFlash.id))
        assertEquals(setOf(TorchMode.OFF, TorchMode.ON), capabilities.supportedTorchModes(withFlash.id))
        assertEquals(setOf(FlashMode.OFF), capabilities.supportedFlashModes(withoutFlash.id))
        assertEquals(setOf(TorchMode.OFF), capabilities.supportedTorchModes(withoutFlash.id))
        engine.closeAndJoin()
    }
}
