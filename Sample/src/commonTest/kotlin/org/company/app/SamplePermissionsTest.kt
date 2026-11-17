package org.company.app

import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sample asks for the microphone its recordings use, and for the gallery only when its image saver
 * puts captures there; a refused camera reaches the screen as a notice rather than the console.
 */
class SamplePermissionsTest {
    @Test
    fun theGalleryIsAskedForOnlyWhenAutoSaveIsOn() {
        assertEquals(
            listOf(CameraKPermission.CAMERA, CameraKPermission.MICROPHONE, CameraKPermission.GALLERY_ADD),
            samplePermissions(autoSave = true),
        )
        assertEquals(
            listOf(CameraKPermission.CAMERA, CameraKPermission.MICROPHONE),
            samplePermissions(autoSave = false),
        )
    }

    @Test
    fun aRefusedCameraIsANoticeAndAGrantedOneIsNot() {
        assertEquals(
            "Camera access was denied",
            permissionNotice(mapOf(CameraKPermission.CAMERA to PermissionStatus.Denied(shouldShowRationale = true))),
        )
        assertEquals(
            "Allow camera access in Settings",
            permissionNotice(mapOf(CameraKPermission.CAMERA to PermissionStatus.PermanentlyDenied)),
        )
        assertNull(
            permissionNotice(
                mapOf(
                    CameraKPermission.CAMERA to PermissionStatus.Granted,
                    CameraKPermission.GALLERY_ADD to PermissionStatus.Denied(shouldShowRationale = false),
                ),
            ),
            "a refused gallery stops nothing the camera shows",
        )
    }

    @Test
    fun aCameraNotAskedYetIsNoNotice() {
        assertNull(
            permissionNotice(mapOf(CameraKPermission.CAMERA to PermissionStatus.NotDetermined)),
            "a fresh desktop run reads NotDetermined before anything was asked",
        )
        assertFalse(cameraGranted(PermissionStatus.NotDetermined))
    }

    @Test
    fun whereOpeningTheCameraAsksTheCameraIsShownBeforeItIsAnswered() {
        // macOS shows its prompt when the camera opens: a permission screen there would wait forever.
        assertTrue(showsCamera(PermissionStatus.NotDetermined, asksWhenOpened = true))
        assertFalse(showsCamera(PermissionStatus.NotDetermined, asksWhenOpened = false))
        assertFalse(showsCamera(PermissionStatus.PermanentlyDenied, asksWhenOpened = true))
        assertFalse(showsCamera(PermissionStatus.Denied(shouldShowRationale = false), asksWhenOpened = true))
        assertTrue(showsCamera(PermissionStatus.Granted, asksWhenOpened = false))
        assertTrue(showsCamera(PermissionStatus.Limited, asksWhenOpened = false))
    }

    @Test
    fun aLimitedCameraIsGranted() {
        assertNull(permissionNotice(mapOf(CameraKPermission.CAMERA to PermissionStatus.Limited)))
        assertTrue(cameraGranted(PermissionStatus.Limited))
    }
}
