@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.contract.AppleEngineDriver
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A recording goes to the configured output directory, the recording's own or the camera's, when set;
 * DefaultOutputDirectoryTest covers the default, Documents/CameraK. Publishing it to Photos is the
 * media saver's job (IS').
 */
class AppOwnedRecordingTest {
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun recordingWritesUnderTheConfiguredOutputDirectory() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val cameraDirectory = "${NSTemporaryDirectory()}cameraK-movies-${NSUUID().UUIDString}"
            written += cameraDirectory
            val engine = driver.createEngine(CameraConfiguration { outputDirectory = cameraDirectory })
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val recording = startEngineRecording(driver, engine).also { written += it.path }

            assertTrue(recording.path.startsWith("$cameraDirectory/"), "not under the camera's output directory")
            engine.close()
        }
    }
}
