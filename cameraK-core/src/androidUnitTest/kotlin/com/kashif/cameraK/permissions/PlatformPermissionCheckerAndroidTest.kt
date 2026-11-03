package com.kashif.cameraK.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Core's Android checker reads the system grant, and below it the record `:cameraK`'s permission
 * request writes: a recorded permanent denial is PermanentlyDenied, an earlier request is Denied, and
 * a permission never asked for is NotDetermined. A grant, which the user may give in Settings, wins
 * and clears the permanent denial.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(InternalCameraKApi::class)
class PlatformPermissionCheckerAndroidTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val context = CameraKPlatformContext(app)
    private val record = PermissionRequestRecord(app)

    private fun status(permission: CameraKPermission) = platformPermissionStatus(context, permission)

    private fun deniedTwice(name: String) {
        record.recordResult(name, granted = false, shouldShowRationale = true)
        record.recordResult(name, granted = false, shouldShowRationale = false)
    }

    @Test
    fun readsTheRecordedPermanentDenial() {
        deniedTwice(Manifest.permission.CAMERA)

        assertEquals(PermissionStatus.PermanentlyDenied, status(CameraKPermission.CAMERA))
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            platformPermissionChecker(context).status(CameraKPermission.CAMERA),
            "the holder's checker reads the same record",
        )
        assertEquals(
            PermissionStatus.NotDetermined,
            status(CameraKPermission.MICROPHONE),
            "the record is per permission",
        )
    }

    @Test
    fun grantClearsTheRecord() {
        deniedTwice(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertEquals(PermissionStatus.Granted, status(CameraKPermission.MICROPHONE))

        // Revoked again in Settings: the app may ask again, so the earlier permanent denial is gone.
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(PermissionStatus.Denied(shouldShowRationale = true), status(CameraKPermission.MICROPHONE))
    }

    @Test
    fun neverAskedIsNotDetermined() {
        assertEquals(PermissionStatus.NotDetermined, status(CameraKPermission.CAMERA))
        assertEquals(PermissionStatus.NotDetermined, status(CameraKPermission.MICROPHONE))

        record.recordResult(Manifest.permission.CAMERA, granted = false, shouldShowRationale = true)
        assertEquals(PermissionStatus.Denied(shouldShowRationale = true), status(CameraKPermission.CAMERA))
    }

    @Test
    @Config(sdk = [29, 35])
    fun galleryAddIsGrantedFromApi29() {
        // MediaStore inserts of the app's own media need no permission from API 29.
        assertEquals(PermissionStatus.Granted, status(CameraKPermission.GALLERY_ADD))
        assertEquals(null, androidPermissionName(CameraKPermission.GALLERY_ADD))
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun galleryAddBelowApi29IsTheStoragePermission() {
        assertEquals(Manifest.permission.WRITE_EXTERNAL_STORAGE, androidPermissionName(CameraKPermission.GALLERY_ADD))
        assertEquals(PermissionStatus.NotDetermined, status(CameraKPermission.GALLERY_ADD))

        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertEquals(PermissionStatus.Granted, status(CameraKPermission.GALLERY_ADD))
    }
}
