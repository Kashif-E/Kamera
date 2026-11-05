@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.provider.MediaStore
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.controller.FakeMediaStore
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The engine's capture is a real file in app-owned storage and nothing else (Decision 10,
 * double-gallery-save): no MediaStore row is created. A configuration can no longer name a gallery
 * folder (CF'); DefaultOutputDirectoryTest covers the default's file, row and scan. Publishing to the gallery is the media saver plugin's job.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureOutputTest {
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val platform = FakeAndroidPlatform()
    private val appOwned = File(platform.context.filesDir, "CameraK").canonicalFile

    @Test
    fun captureReturnsRealFileInAppOwnedDirectory() = runTest {
        val image = capture(CameraConfiguration())

        val file = File(image.path)
        assertEquals(appOwned, file.canonicalFile.parentFile, "the capture is not in app-owned storage")
        assertTrue(file.isFile, "the capture's path is not a real file")
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        assertEquals(ImageFormat.JPEG, image.format)
    }

    @Test
    fun captureCreatesNoMediaStoreRow() = runTest {
        capture(CameraConfiguration())

        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "a capture created a gallery row")
    }

    private suspend fun TestScope.capture(config: CameraConfiguration): CapturedImage {
        val engine = platform.engine(testScheduler, config)
        open(platform, engine)
        val capture = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.last().deliverSaved(byteArrayOf(1, 2, 3))
        platform.settle(testScheduler)
        val image = assertIs<CameraKResult.Success<CapturedImage>>(capture.await(), "the capture").value
        engine.closeAndJoin()
        return image
    }
}
