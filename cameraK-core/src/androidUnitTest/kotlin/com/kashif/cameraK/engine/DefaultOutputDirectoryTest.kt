@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.provider.MediaStore
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.controller.FakeMediaStore
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * With no output directory configured, a still and a recording land in the app-owned filesDir/CameraK
 * (Decision 10, design §6.1): CameraX writes a file, no MediaStore row is created and nothing reaches
 * the media scanner. A configuration can no longer name a gallery folder (CF'), so this is the only
 * default there is; publishing is the media saver's.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultOutputDirectoryTest {
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val platform = FakeAndroidPlatform()
    private val appOwned = File(platform.context.filesDir, "CameraK").canonicalFile

    @Test
    fun defaultIsTheDocumentsLocation() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration())
        open(platform, engine)
        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.last().deliverSaved(byteArrayOf(1, 2, 3))
        platform.settle(testScheduler)
        val image = assertIs<CameraKResult.Success<CapturedImage>>(capture.await(), "the capture").value
        engine.closeAndJoin()

        assertNotNull(platform.backend.captures.last().options.file, "the capture asked CameraX for a gallery row")
        assertEquals(appOwned, File(image.path).canonicalFile.parentFile, "the capture is not in filesDir/CameraK")
        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "the capture created a gallery row")
        assertEquals(emptySet(), ShadowMediaScannerConnection.getSavedPaths(), "the capture was scanned")
    }

    @Test
    fun aRecordingDefaultsToTheAppOwnedLocation() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration())
        open(platform, engine)

        val (recording, path) = startRecording(platform, engine)

        assertEquals(appOwned, File(path).canonicalFile.parentFile, "the recording is not in filesDir/CameraK")
        recording.finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "the recording created a gallery row")
        assertEquals(emptySet(), ShadowMediaScannerConnection.getSavedPaths(), "the recording was scanned")
    }
}
