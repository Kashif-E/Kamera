package com.kashif.cameraK.controller

import android.Manifest
import android.content.Context
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.permissions.storagePermissionGranted
import com.kashif.cameraK.result.ImageCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A capture into the shared gallery (PICTURES, DCIM) goes through MediaStore, which needs no
 * permission from API 29 and is the only way to write there once scoped storage is enforced. Writing
 * a java.io.File into the public directory fails with EACCES on API 29 and needs
 * WRITE_EXTERNAL_STORAGE below it.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @After
    fun tearDown() {
        hostScope.cancel()
        processingExecutor.shutdownNow()
    }

    @Test
    fun picturesCapture_isAMediaStoreImageInPicturesCameraK() {
        val result = capture(Directory.PICTURES)

        val (row, values) = singleRow()
        assertEquals("images", row.pathSegments[1], "the capture is not in the images collection: $row")
        assertEquals("Pictures/CameraK", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(0, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertContentEquals(jpeg, mediaStore.bytesOf(row))
        assertEquals(ImageCaptureResult.SuccessWithUri(row.toString()), result)
    }

    @Test
    fun dcimCapture_isAMediaStoreImageInDcimCameraK() {
        capture(Directory.DCIM)

        val (_, values) = singleRow()
        assertEquals("DCIM/CameraK", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    fun galleryCapture_writesNoFileIntoPublicStorageAndRequestsNoScan() {
        capture(Directory.PICTURES)

        val publicFolder = File(publicDirectory(Environment.DIRECTORY_PICTURES), "CameraK")
        assertEquals(emptyList(), publicFolder.list().orEmpty().toList(), "a file was written into public Pictures")
        assertEquals(emptyList(), ShadowMediaScannerConnection.getSavedPaths().toList(), "a media scan was requested")
    }

    /** Below API 29 there is no RELATIVE_PATH; the row names its file in the public folder instead. */
    @Test
    @Config(sdk = [28])
    fun dcimCapture_belowApi29_isAMediaStoreImageWhoseFileIsInDcimCameraK() {
        capture(Directory.DCIM)

        val (_, values) = singleRow()
        assertNull(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        val file = File(values.getAsString(MediaStore.MediaColumns.DATA))
        assertEquals(
            File(publicDirectory(Environment.DIRECTORY_DCIM), "CameraK").canonicalFile,
            file.parentFile?.canonicalFile,
        )
        assertEquals(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME), file.name)
    }

    @Test
    fun galleryCapture_stillReachesCaptureListeners() {
        val delivered = Collections.synchronizedList(mutableListOf<ByteArray>())
        val controller =
            testController(
                context,
                backend,
                imageProcessingExecutor = processingExecutor,
                directory = Directory.PICTURES,
            )
        controller.addImageCaptureListener { delivered += it }

        capture(controller)
        processingExecutor.shutdown()
        assertTrue(processingExecutor.awaitTermination(5, TimeUnit.SECONDS), "listener work did not finish")

        assertEquals(1, delivered.size, "the listener did not get the gallery capture")
        assertContentEquals(jpeg, delivered.single())
    }

    @Test
    @Config(sdk = [29])
    fun storagePermission_isNotNeededFromApi29() {
        assertTrue(storagePermissionGranted(context), "API 29 writes through MediaStore without WRITE_EXTERNAL_STORAGE")
    }

    @Test
    @Config(sdk = [28])
    fun storagePermission_isNeededBelowApi29() {
        assertFalse(storagePermissionGranted(context))

        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(storagePermissionGranted(context))
    }

    /** The gallery path below API 29 needs the permission, and only there, so the library declares it capped at 28. */
    @Test
    fun libraryManifest_declaresWriteExternalStorageOnlyUpToApi28() {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/androidMain/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).map { permissions.item(it) as org.w3c.dom.Element }
            .associate { it.getAttributeNS(ANDROID_NS, "name") to it.getAttributeNS(ANDROID_NS, "maxSdkVersion") }

        assertEquals("28", declared[Manifest.permission.WRITE_EXTERNAL_STORAGE], "declared permissions: $declared")
    }

    private fun capture(directory: Directory): ImageCaptureResult =
        capture(testController(context, backend, imageProcessingExecutor = processingExecutor, directory = directory))

    private fun capture(controller: CameraController): ImageCaptureResult {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.last().deliverSaved(jpeg)
        idleMainLooper()
        assertTrue(result.isCompleted, "takePictureToFile did not return")
        return runBlocking { result.await() }
    }

    private fun singleRow() = mediaStore.rows.entries.let { rows ->
        assertEquals(1, rows.size, "expected one gallery entry, found ${rows.map { it.key }}")
        rows.single().toPair()
    }

    @Suppress("DEPRECATION") // The public directories are what a gallery capture must not write to directly.
    private fun publicDirectory(type: String): File = Environment.getExternalStoragePublicDirectory(type)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
