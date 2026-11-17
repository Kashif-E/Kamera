@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import androidx.camera.core.ImageAnalysis
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform
import dev.kashifmehmood.camerak.engine.FakeImageProxy
import dev.kashifmehmood.camerak.engine.open
import dev.kashifmehmood.camerak.engine.startRecording
import dev.kashifmehmood.camerak.frames.FrameSpec
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Plugins come and go (a QR scanner pauses, OCR starts) while the camera runs. Changing who receives
 * frames must not rebind the camera, which blanks the preview, and must leave exactly one
 * ImageAnalysis bound, feeding exactly the registered consumers.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyzerRegistrationTest {
    private val platform = FakeAndroidPlatform()
    private val backend = platform.backend
    private var firstFrames = 0
    private var secondFrames = 0

    @Test
    fun changingConsumersOnARunningCamera_doesNotRebind() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        open(platform, engine)
        val first = engine.frames.register(FrameSpec(synchronous = true)) { firstFrames++ }
        platform.settle(testScheduler)
        val bindsBefore = backend.binding.bindCalls

        val second = engine.frames.register(FrameSpec(synchronous = true)) { secondFrames++ }
        platform.settle(testScheduler)
        first.close()
        platform.settle(testScheduler)

        assertEquals(bindsBefore, backend.binding.bindCalls, "changing frame consumers rebound the camera")
        deliverFrameToBoundAnalysis()
        assertEquals(0, firstFrames, "an unregistered analyzer still receives frames")
        assertEquals(1, secondFrames, "a registered analyzer does not receive frames")
        second.close()
        engine.closeAndJoin()
    }

    @Test
    fun changingConsumersWhileRecording_keepsOneImageAnalysisFeedingExactlyTheRegisteredOnes() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        open(platform, engine)
        val first = engine.frames.register(FrameSpec(synchronous = true)) { firstFrames++ }
        platform.settle(testScheduler)
        startRecording(platform, engine, VideoConfiguration { enableAudio = false })

        val second = engine.frames.register(FrameSpec(synchronous = true)) { secondFrames++ }
        platform.settle(testScheduler)
        first.close()
        platform.settle(testScheduler)

        deliverFrameToBoundAnalysis()
        assertEquals(0, firstFrames, "an unregistered analyzer still receives frames during a recording")
        assertEquals(1, secondFrames, "a registered analyzer does not receive frames during a recording")
        second.close()
        engine.close()
    }

    /** One frame from the camera, through every ImageAnalysis that is bound; there must be exactly one. */
    private fun deliverFrameToBoundAnalysis() {
        val bound = backend.binding.bound.filterIsInstance<ImageAnalysis>()
        assertEquals(1, bound.size, "expected exactly one bound ImageAnalysis, found ${bound.size}")
        backend.analyzers[bound.single()]?.analyze(FakeImageProxy.yuv().proxy)
    }
}
