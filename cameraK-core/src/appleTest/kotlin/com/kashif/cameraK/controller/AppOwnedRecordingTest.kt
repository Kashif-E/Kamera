@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recording is an app-owned file whatever the Directory (Decision 10): PICTURES and DCIM record into
 * Documents/CameraK, never a temporary file waiting for a Photos import, and the result is that file.
 * Publishing it to Photos is the media saver's job (IS'). The configured output directory, the
 * recording's own or the camera's, is where it goes when set.
 */
class AppOwnedRecordingTest {
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun picturesConfiguredRecordingIsAnAppOwnedFileAndNeverReachesPhotos() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine(CameraConfiguration(directory = Directory.PICTURES))
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val recording = startEngineRecording(driver, engine).also { written += it.path }
            assertTrue(
                recording.path.contains("/Documents/CameraK/"),
                "a PICTURES recording is not in Documents/CameraK",
            )
            val stop = async { engine.stopRecording() }
            driver.settle()
            finishRecording(driver, recording)
            repeat(20) { if (!stop.isCompleted) driver.settle() }

            val video = assertIs<CameraKResult.Success<RecordedVideo>>(stop.await()).value
            assertEquals(recording.path, video.path, "the recording's result is its file")
            assertFalse(video.path.startsWith("ph://"), "a Photos identifier")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(video.path), "the file is gone")
            engine.close()
        }
    }

    @Test
    fun recordingWritesUnderTheConfiguredOutputDirectory() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val cameraDirectory = "${NSTemporaryDirectory()}cameraK-movies-${NSUUID().UUIDString}"
            written += cameraDirectory
            val engine = driver.createEngine(CameraConfiguration(outputDirectory = cameraDirectory))
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val recording = startEngineRecording(driver, engine).also { written += it.path }

            assertTrue(recording.path.startsWith("$cameraDirectory/"), "not under the camera's output directory")
            engine.close()
        }
    }
}
