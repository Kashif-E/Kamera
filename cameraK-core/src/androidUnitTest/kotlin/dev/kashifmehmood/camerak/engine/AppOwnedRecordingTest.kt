@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Recordings are app-owned (Decision 10, w6 G3), and core never hands a recording to the media
 * scanner (#158, which scanned private recordings too). The engine's default folder is
 * DefaultOutputDirectoryTest's. Publishing is the media saver plugin's job.
 */
@RunWith(RobolectricTestRunner::class)
class AppOwnedRecordingTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun noRecordingIsEverPassedToTheMediaScanner() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val (recording, path) = startRecording(platform, engine, VideoConfiguration())
        assertTrue(
            File(path).canonicalPath.startsWith(platform.context.filesDir.canonicalPath + File.separator),
            "the recording is not app-owned: $path",
        )

        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        recording.finalize()
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<RecordedVideo>>(stop.await(), "setup: the recording")

        assertEquals(emptySet(), ShadowMediaScannerConnection.getSavedPaths(), "a recording was sent to the scanner")
        engine.closeAndJoin()
    }
}
