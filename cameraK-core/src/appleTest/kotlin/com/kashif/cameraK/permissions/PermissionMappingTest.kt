package com.kashif.cameraK.permissions

import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSThread
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS statuses: AVFoundation's for the camera and the microphone, Photos' add-only level for the
 * gallery. Denied is PermanentlyDenied, because iOS shows its prompt once. Request results reach the
 * caller on the main thread, whatever queue the system answers on.
 */
@OptIn(InternalCameraKApi::class)
class PermissionMappingTest {
    private val context = CameraKPlatformContext()

    @Test
    fun avDeniedIsPermanentlyDenied() {
        val expected = mapOf(
            AVAuthorizationStatusAuthorized to PermissionStatus.Granted,
            AVAuthorizationStatusDenied to PermissionStatus.PermanentlyDenied,
            AVAuthorizationStatusRestricted to PermissionStatus.Restricted,
            AVAuthorizationStatusNotDetermined to PermissionStatus.NotDetermined,
        )
        for ((raw, status) in expected) {
            val platform = FakeApplePermissionPlatform(capture = mutableMapOf(AVMediaTypeVideo to raw))
            withPlatform(platform) {
                assertEquals(
                    status,
                    platformPermissionStatus(context, CameraKPermission.CAMERA),
                    "AVAuthorizationStatus $raw",
                )
                assertEquals(
                    status,
                    platformPermissionChecker(context).status(CameraKPermission.CAMERA),
                    "the holder's checker, AVAuthorizationStatus $raw",
                )
            }
            assertEquals(listOf(AVMediaTypeVideo, AVMediaTypeVideo), platform.captureReads)
        }
    }

    @Test
    fun microphoneReadsTheAudioMediaType() {
        val platform = FakeApplePermissionPlatform(
            capture = mutableMapOf(
                AVMediaTypeVideo to AVAuthorizationStatusAuthorized,
                AVMediaTypeAudio to AVAuthorizationStatusDenied,
            ),
        )

        val status = withPlatform(platform) { platformPermissionStatus(context, CameraKPermission.MICROPHONE) }

        assertEquals(PermissionStatus.PermanentlyDenied, status)
        assertEquals(listOf(AVMediaTypeAudio), platform.captureReads)
    }

    @Test
    fun galleryAddReadsTheAddOnlyLevel() {
        val expected = mapOf(
            PHAuthorizationStatusAuthorized to PermissionStatus.Granted,
            PHAuthorizationStatusLimited to PermissionStatus.Limited,
            PHAuthorizationStatusDenied to PermissionStatus.PermanentlyDenied,
            PHAuthorizationStatusRestricted to PermissionStatus.Restricted,
            PHAuthorizationStatusNotDetermined to PermissionStatus.NotDetermined,
        )
        for ((raw, status) in expected) {
            val platform = FakeApplePermissionPlatform(photo = raw)
            withPlatform(platform) {
                assertEquals(
                    status,
                    platformPermissionStatus(context, CameraKPermission.GALLERY_ADD),
                    "PHAuthorizationStatus $raw",
                )
            }
            assertEquals(listOf(PHAccessLevelAddOnly), platform.photoReads)
            assertEquals(emptyList(), platform.captureReads)
        }
    }

    @Test
    fun galleryAddBelowIos14ReadsNothing() {
        // Below iOS 14 the library calls no authorization API for the gallery (least privilege): the
        // saver's performChanges raises the system's add-only prompt, so the status is not yet known.
        val platform = FakeApplePermissionPlatform(photo = PHAuthorizationStatusAuthorized, photoAccessLevels = false)

        withPlatform(platform) {
            assertEquals(
                PermissionStatus.NotDetermined,
                platformPermissionStatus(context, CameraKPermission.GALLERY_ADD),
            )
            assertEquals(
                PermissionStatus.NotDetermined,
                platformPermissionChecker(context).status(CameraKPermission.GALLERY_ADD),
                "the holder's checker",
            )
        }

        assertEquals(emptyList(), platform.photoReads, "read the photo library's full-access status below iOS 14")
    }

    @Test
    fun galleryAddBelowIos14RequestsNothing() {
        // The fake fails on any photo request: below iOS 14 that would be requestAuthorization, full access.
        val platform = FakeApplePermissionPlatform(photoAccessLevels = false)
        val result = atomic<Map<CameraKPermission, PermissionStatus>?>(null)

        withPlatform(platform) {
            requestPlatformPermissions(context, listOf(CameraKPermission.GALLERY_ADD)) { result.value = it }
            awaitOnMain("the request's result") { result.value != null }
        }

        assertEquals(mapOf(CameraKPermission.GALLERY_ADD to PermissionStatus.NotDetermined), result.value)
        assertEquals(emptyList(), platform.photoReads)
    }

    @Test
    fun requestCallbackRunsOnMain() {
        val platform = FakeApplePermissionPlatform()
        val onMain = atomic<Boolean?>(null)
        val result = atomic<Map<CameraKPermission, PermissionStatus>?>(null)

        withPlatform(platform) {
            requestPlatformPermissions(context, listOf(CameraKPermission.CAMERA)) {
                onMain.value = NSThread.isMainThread
                result.value = it
            }
            awaitOnMain("the request's result") { result.value != null }
        }

        assertEquals(listOf(AVMediaTypeVideo), platform.captureRequests)
        assertTrue(onMain.value == true, "the result was delivered off the main thread")
        assertEquals(mapOf(CameraKPermission.CAMERA to PermissionStatus.Granted), result.value)
    }
}
