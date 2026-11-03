@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.ImageAnalysis
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The frame pipeline's ImageAnalysis binding, Decision 14's engine half. A4' adds the rest of this class. */
@RunWith(RobolectricTestRunner::class)
class FramePipelineTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun analysisBoundAtStartWhenFlagSet() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration(frameAnalysis = true))

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        assertEquals(1, platform.backend.binding.bindCalls)
        assertEquals(
            1,
            platform.backend.binding.bound.filterIsInstance<ImageAnalysis>().size,
            "the first bind left out the declared ImageAnalysis",
        )
        assertTrue(platform.backend.analyzers.isEmpty(), "frames are delivered with no consumer registered")
        engine.closeAndJoin()
    }
}
