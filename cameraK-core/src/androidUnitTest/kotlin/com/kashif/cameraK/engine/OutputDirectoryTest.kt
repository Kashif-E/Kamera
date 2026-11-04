@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `CameraConfiguration.outputDirectory` is where the engine writes stills and, unless the recording
 * names its own, recordings (w6-design-decisions, output directory; §6.1).
 */
@RunWith(RobolectricTestRunner::class)
class OutputDirectoryTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun stillsAndRecordingsGoUnderTheConfiguredOutputDirectory() = runTest {
        val configured = File(platform.context.filesDir, "app-media").canonicalFile
        val ownVideos = File(platform.context.filesDir, "clips").canonicalFile
        val engine = platform.engine(testScheduler, CameraConfiguration(outputDirectory = configured.path))
        open(platform, engine)

        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.single().deliverSaved(byteArrayOf(1))
        platform.settle(testScheduler)
        val image = assertIs<CameraKResult.Success<CapturedImage>>(capture.await()).value
        assertEquals(configured, File(image.path).canonicalFile.parentFile, "the still")

        val (first, firstPath) = startRecording(platform, engine)
        assertEquals(configured, File(firstPath).canonicalFile.parentFile, "a recording that names no directory")
        first.finalize()
        platform.settle(testScheduler)

        val (second, secondPath) = startRecording(
            platform,
            engine,
            VideoConfiguration(outputDirectory = ownVideos.path),
        )
        assertEquals(ownVideos, File(secondPath).canonicalFile.parentFile, "a recording that names its own")
        second.finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }
}
