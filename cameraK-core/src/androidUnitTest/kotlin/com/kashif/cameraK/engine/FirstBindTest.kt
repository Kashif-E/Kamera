@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.ImageAnalysis
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * One bind to the first frame (Decision 14). With `frameAnalysis` declared, the first bind includes
 * the ImageAnalysis, so an analyzer that registers while the start runs, as PluginHost attaches the
 * Sample's plugins at the first Ready, only joins as a consumer. Without the flag, the first
 * registration on a bound camera rebinds once.
 */
@RunWith(RobolectricTestRunner::class)
class FirstBindTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun oneBindBeforeTheFirstFrameWhenFrameAnalysisIsSet() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        platform.backend.binding.holdNextOpen = true
        val start = async { engine.start(desired) }
        platform.settle(testScheduler)

        engine.frames.register { }
        platform.settle(testScheduler)
        platform.reportOpen()
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), start.await())
        platform.backend.deliverPreviewFrame()
        platform.settle(testScheduler)

        assertEquals(1, platform.backend.binding.bindCalls, "binds before the first frame")
        val analysis = platform.backend.binding.bound.filterIsInstance<ImageAnalysis>().single()
        assertNotNull(
            platform.backend.analyzers[analysis],
            "the registered analyzer does not consume the bound analysis",
        )
        engine.closeAndJoin()
    }

    // Characterization: the late-registration fallback of Decision 14, which A4' refines.
    @Test
    fun withTheFlagOffTheFirstRegistrationRebindsOnce() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        assertEquals(1, platform.backend.binding.bindCalls, "setup: one bind")

        engine.frames.register { }
        platform.settle(testScheduler)
        engine.frames.register(FrameSpec(synchronous = true)) { }
        platform.settle(testScheduler)

        assertEquals(2, platform.backend.binding.bindCalls, "the first registration rebinds once, the second never")
        assertEquals(1, platform.backend.binding.bound.filterIsInstance<ImageAnalysis>().size)
        engine.closeAndJoin()
    }
}
