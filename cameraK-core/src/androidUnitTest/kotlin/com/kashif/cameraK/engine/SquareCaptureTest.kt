@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.graphics.Color
import android.util.Rational
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 1:1 is the square file the 1:1 ViewPort gives over a 4:3 stream (android-1x1-viewport-reencode):
 * CameraX crops the capture to the ViewPort and re-encodes it, so the file itself is square and there
 * is no crop rect to report. The engine says so in its capabilities.
 */
@RunWith(RobolectricTestRunner::class)
class SquareCaptureTest {
    private val platform = FakeAndroidPlatform()
    private val square = CameraConfiguration { aspectRatio = AspectRatio.RATIO_1_1 }

    @Test
    fun oneToOneBindsA4By3StreamUnderASquareViewPort() = runTest {
        val engine = platform.engine(testScheduler, square)
        open(platform, engine)

        assertEquals(Rational(1, 1), platform.backend.binding.viewPorts.last()!!.aspectRatio, "the ViewPort")
        val capture = platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
        assertEquals(
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
            capture.resolutionSelector?.aspectRatioStrategy,
            "the stream under the square ViewPort",
        )
        engine.closeAndJoin()
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun capturedSizeIsReadFromTheWrittenFile() = runTest {
        val engine = platform.engine(testScheduler, square, imageProcessing = DirectExecutor())
        open(platform, engine)

        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.single().deliverSaved(jpeg(platform.context.cacheDir, 48, 48) { _, _ -> Color.GRAY })
        platform.settle(testScheduler)

        val image = assertIs<CameraKResult.Success<CapturedImage>>(capture.await()).value
        assertEquals(48 to 48, image.width to image.height, "the size of the written file")
        engine.closeAndJoin()
    }

    @Test
    fun capabilityFlagIsSet() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)

        assertTrue(engine.capabilities.value.supportsSquareCapture, "supportsSquareCapture")
        assertTrue(AspectRatio.RATIO_1_1 in engine.capabilities.value.supportedAspectRatios, "RATIO_1_1")
        engine.closeAndJoin()
    }
}

/** Runs each task on the calling thread, so image processing finishes inside the test's settle. */
internal class DirectExecutor : AbstractExecutorService() {
    private var shutdown = false

    override fun execute(command: Runnable) = command.run()

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shutdown = true }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}
