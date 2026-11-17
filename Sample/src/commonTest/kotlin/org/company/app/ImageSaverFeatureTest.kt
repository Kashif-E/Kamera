package org.company.app

import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.StorageFailure
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.result.CameraKResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The capture's "Photo taken" says nothing of the gallery: a save the gallery refused, or one the
 * app may not make, is told to the user from the image saver's results.
 */
class ImageSaverFeatureTest {
    @Test
    fun aFailedGallerySaveIsToldAndASavedOneIsNot() = runTest {
        val results = flowOf(
            CameraKResult.Success("saved"),
            CameraKResult.Failure(
                CameraKError.Permission(
                    CameraKPermission.GALLERY_ADD,
                    PermissionStatus.Denied(shouldShowRationale = false),
                ),
            ),
            CameraKResult.Failure(CameraKError.Storage(StorageFailure.GALLERY_IMPORT_FAILED)),
        )

        assertEquals(
            listOf("Allow access to Photos to save to the gallery", "Could not save the photo to the gallery"),
            galleryNotices(results).toList(),
        )
    }
}
