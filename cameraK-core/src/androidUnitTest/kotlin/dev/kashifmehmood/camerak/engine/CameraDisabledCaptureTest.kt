@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import android.graphics.Color
import androidx.camera.core.CameraState
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * GitHub #189: when the system takes the camera away (ERROR_CAMERA_DISABLED: device policy, or the
 * app's uid going idle), CameraX closes it and keeps a capture's request queued for a camera it does
 * not reopen. The capture ends with the camera's error, and a file CameraX writes for it later, once
 * the camera opens again, is deleted rather than left unreported.
 */
@RunWith(RobolectricTestRunner::class)
class CameraDisabledCaptureTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun aCaptureWaitingWhenTheCameraIsDisabledEndsWithCameraDisabledAndLeavesNoFile() = runTest {
        val stills = File(platform.context.filesDir, "stills")
        val engine = platform.engine(testScheduler, CameraConfiguration { outputDirectory = stills.path })
        open(platform, engine)
        val capture = async { engine.capture() }
        platform.settle(testScheduler)

        checkNotNull(platform.camera).report(
            CameraState.create(
                CameraState.Type.CLOSING,
                CameraState.StateError.create(CameraState.ERROR_CAMERA_DISABLED),
            ),
        )
        platform.settle(testScheduler)

        val failed = assertIs<EngineState.Failed>(engine.state.value)
        assertIs<CameraKError.CameraDisabled>(failed.error)
        assertTrue(capture.isCompleted, "the capture waits for a camera CameraX will not reopen")
        assertSame(failed.error, assertIs<CameraKResult.Failure>(capture.await()).error)

        platform.backend.captures.single().deliverSaved(byteArrayOf(1, 2, 3))
        platform.settle(testScheduler)
        assertEquals(emptyList(), stills.listFiles().orEmpty().toList(), "a file nobody was told about")
        engine.closeAndJoin()
    }

    @Test
    fun aCaptureStillMakingItsFolderWhenTheCameraIsDisabledNeverReachesCameraX() = runTest {
        val io = HeldDispatcher(StandardTestDispatcher(testScheduler))
        val stills = File(platform.context.filesDir, "stills")
        val engine = platform.engine(
            testScheduler,
            CameraConfiguration {
                outputDirectory = stills.path
            },
            background = io,
        )
        open(platform, engine)
        // The start made the folder; with it gone, the capture makes it again on io, off the main thread.
        assertTrue(stills.deleteRecursively(), "setup: the app deletes the output folder")
        io.held = true
        val capture = async { engine.capture() }
        platform.settle(testScheduler)

        disable()
        platform.settle(testScheduler)
        io.release()
        platform.settle(testScheduler)

        val failed = assertIs<EngineState.Failed>(engine.state.value)
        assertTrue(
            capture.isCompleted,
            "a capture issued just before the failure waits for a camera CameraX will not reopen",
        )
        assertSame(failed.error, assertIs<CameraKResult.Failure>(capture.await()).error)
        assertTrue(platform.backend.captures.isEmpty(), "the capture reached CameraX after the camera was disabled")
        assertEquals(emptyList(), stills.listFiles().orEmpty().toList(), "a file nobody was told about")
        engine.closeAndJoin()
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPngWrittenAfterItsCaptureFailedIsDeleted() = runTest {
        val processing = HeldExecutor()
        val stills = File(platform.context.filesDir, "stills")
        val engine = platform.engine(
            testScheduler,
            CameraConfiguration {
                outputDirectory = stills.path
                imageFormat = ImageFormat.PNG
            },
            imageProcessing = processing,
        )
        open(platform, engine)
        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        // CameraX saved the JPEG; turning it into a PNG waits on the image-processing executor.
        platform.backend.captures.single().deliverSaved(jpeg(platform.context.cacheDir, 16, 8) { _, _ -> Color.GREEN })
        platform.settle(testScheduler)

        disable()
        platform.settle(testScheduler)
        assertIs<CameraKResult.Failure>(capture.await())
        processing.runAll()
        platform.settle(testScheduler)

        assertEquals(emptyList(), stills.listFiles().orEmpty().toList(), "a file nobody was told about")
        engine.closeAndJoin()
    }

    private fun disable() = checkNotNull(platform.camera).report(
        CameraState.create(CameraState.Type.CLOSING, CameraState.StateError.create(CameraState.ERROR_CAMERA_DISABLED)),
    )

    /** Dispatches to [delegate], or, while [held], keeps each block until [release]. */
    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var held = false
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) waiting += context to block else delegate.dispatch(context, block)
        }

        fun release() {
            held = false
            waiting.toList().also { waiting.clear() }.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    /** Runs nothing until [runAll]. */
    private class HeldExecutor : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() = tasks.toList().also { tasks.clear() }.forEach(Runnable::run)

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> = tasks.toList().also { shutdown = true }

        override fun isShutdown() = shutdown

        override fun isTerminated() = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}
