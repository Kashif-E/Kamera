@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Output directories are created and checked off the main thread, on the engine's io dispatcher
 * (memory-manager-cargo-cult), and a directory that cannot be created is `Storage(NOT_WRITABLE)`
 * before CameraX is asked for anything (error-info-destroyed: the mkdirs result is no longer dropped).
 */
@RunWith(RobolectricTestRunner::class)
class OutputFileFactoryTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun failedMkdirsIsStorageNotWritableBeforeCameraXIsCalled() = runTest {
        // A directory under a regular file can never be created.
        val blocker = File(platform.context.filesDir, "blocker").apply { writeText("not a directory") }
        val engine = platform.engine(testScheduler, CameraConfiguration(outputDirectory = File(blocker, "out").path))
        open(platform, engine)

        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        val captureError = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(capture.await()).error)
        assertEquals(StorageFailure.NOT_WRITABLE, captureError.kind)
        assertTrue(platform.backend.captures.isEmpty(), "the capture reached CameraX without a directory")

        val record = async { engine.startRecording(VideoConfiguration()) }
        platform.settle(testScheduler)
        val recordError = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(record.await()).error)
        assertEquals(StorageFailure.NOT_WRITABLE, recordError.kind)
        assertTrue(platform.backend.recordings.isEmpty(), "the recording reached CameraX without a directory")
        engine.closeAndJoin()
    }

    @Test
    fun directoryCreatedOffTheMainThread() = runTest {
        val stills = File(platform.context.filesDir, "stills")
        val videos = File(platform.context.filesDir, "videos")
        val io = WatchingDispatcher(StandardTestDispatcher(testScheduler), listOf(stills, videos))
        val engine = platform.engine(testScheduler, CameraConfiguration(outputDirectory = stills.path), background = io)
        open(platform, engine)

        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.single().deliverSaved(byteArrayOf(1))
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(capture.await(), "setup: the capture")
        startRecording(platform, engine, VideoConfiguration(outputDirectory = videos.path))

        assertEquals(setOf(stills, videos), io.created, "directories created outside the io dispatcher")
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    /** Runs blocks on [delegate] and records which of [watched] came into existence inside one. */
    private class WatchingDispatcher(private val delegate: CoroutineDispatcher, private val watched: List<File>) :
        CoroutineDispatcher() {
        val created = mutableSetOf<File>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                val before = watched.filter { it.isDirectory }.toSet()
                block.run()
                created += watched.filter { it.isDirectory && it !in before }
            }
        }
    }
}
