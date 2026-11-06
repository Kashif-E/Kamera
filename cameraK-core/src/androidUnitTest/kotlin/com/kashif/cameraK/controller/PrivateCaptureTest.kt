@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
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
 * With no opt-in, a capture stays in app-private storage: an app using CameraK for ID selfies,
 * document scans or receipts must not leak them into the shared gallery, where any app holding
 * READ_MEDIA_IMAGES can read them. The private option is internal storage (`filesDir`), not
 * app-specific external storage, which can sit on removable or adoptable media.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val filesDir = context.filesDir.canonicalFile

    // The configuration's app-owned default, which 1.x's DOCUMENTS default became (DefaultOutputDirectoryTest).
    @Test
    fun defaultCapture_staysOutOfTheGallery() = runTest {
        val path = capture().path

        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "a default capture was published")
        assertTrue(File(path).canonicalPath.startsWith(filesDir.path + File.separator), "$path is not in $filesDir")
    }

    @Test
    fun defaultCapture_isInInternalStorage() = runTest {
        val path = capture().path

        assertEquals(File(filesDir, "CameraK"), File(path).canonicalFile.parentFile)
    }

    /** Photos and videos that stay private are both written here. */
    @Test
    fun privateCaptureFolder_isInInternalStorage() {
        assertEquals(File(filesDir, "CameraK"), appCaptureDirectory(context).canonicalFile)
    }

    private suspend fun TestScope.capture(): CapturedImage {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(testScheduler, CameraConfiguration())
        open(platform, engine)
        val result = async { engine.capture() }
        platform.settle(testScheduler)
        platform.backend.captures.last().deliverSaved(byteArrayOf(1, 2, 3))
        platform.settle(testScheduler)
        // The engine reads the written file's size on its image-processing thread before it returns.
        val image = assertIs<CameraKResult.Success<CapturedImage>>(result.await()).value
        engine.closeAndJoin()
        return image
    }
}
