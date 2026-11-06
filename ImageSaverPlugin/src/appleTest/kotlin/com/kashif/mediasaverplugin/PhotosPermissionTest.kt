@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.mediasaverplugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * iOS saves at NotDetermined: Photos raises its add-only prompt inside `performChanges`. That is
 * also how a save goes ahead below iOS 14, where core reports NotDetermined; the saver's own status
 * seam drives it here. A prompt the user refuses is a permission failure, not a storage one.
 */
class PhotosPermissionTest {
    private val background = CoroutineScope(Dispatchers.Default + Job())
    private val bytes = ByteArray(16) { it.toByte() }
    private val path = appOwnedFile(bytes, "jpg")

    @AfterTest
    fun tearDown() {
        deleteFile(path)
        background.cancel()
    }

    private fun save(photos: FakePhotoLibrary, permission: ScriptedPermission): CameraKResult<SavedImage> {
        val sut = MediaSaverPlugin(MediaSaverConfig(), AppleGalleryStore(photos), permission)
        val done = background.completesWithin(BOUND) { sut.save(FakeCaptures.image(path = path)) }
        return assertNotNull(done, "save did not return within $BOUND").value
    }

    @Test
    fun notDeterminedProceedsAndPerformChangesPrompts() {
        val photos = FakePhotoLibrary()

        val result = save(photos, ScriptedPermission(PermissionStatus.NotDetermined))

        assertIs<CameraKResult.Success<SavedImage>>(result, "a save at NotDetermined")
        assertEquals(1, photos.imports.value.size, "the save at NotDetermined never reached performChanges")
    }

    @Test
    fun aPromptRefusedInsidePerformChangesIsPermissionPermanentlyDenied() {
        val photos = FakePhotoLibrary(createdIdentifier = null, createError = photosError(3311))

        val result =
            save(photos, ScriptedPermission(PermissionStatus.NotDetermined, PermissionStatus.PermanentlyDenied))

        val error = assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraKPermission.GALLERY_ADD, error.permission)
        assertEquals(PermissionStatus.PermanentlyDenied, error.status)
        assertIs<PhotosException>(error.cause)
        assertContentEquals(bytes, fileBytes(path), "the app-owned file was touched")
    }

    @Test
    fun permanentlyDeniedNeverReachesPhotos() {
        val photos = FakePhotoLibrary()

        val result = save(photos, ScriptedPermission(PermissionStatus.PermanentlyDenied))

        assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(0, photos.imports.value.size, "a refused save reached Photos")
    }
}
