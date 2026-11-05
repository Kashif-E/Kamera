@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.controller.backCamera
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AVFoundation's didDropSampleBuffer is counted per registration (design §8). */
class DroppedFramesTest {
    private val driver = AppleEngineDriver()

    @Test
    fun didDropSampleBufferIsCounted() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val asynchronous = engine.frames.register(FrameSpec()) { }
        val synchronous = engine.frames.register(FrameSpec(synchronous = true)) { }
        driver.settle()

        assertTrue(driver.dropFrame(), "no frame output heard the first drop")
        assertTrue(driver.dropFrame(), "no frame output heard the second drop")
        assertEquals(
            listOf(2, 2),
            listOf(asynchronous, synchronous).map { (it as AppleFrameRegistration).droppedFrames },
            "drops counted by each registration",
        )
        asynchronous.close()
        synchronous.close()
        engine.close()
    }
}
