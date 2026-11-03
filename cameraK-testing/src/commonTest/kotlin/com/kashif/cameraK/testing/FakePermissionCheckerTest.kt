package com.kashif.cameraK.testing

import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakePermissionCheckerTest {
    @Test
    fun grantsEveryPermissionUntilTheTestSetsOne() {
        val checker = FakePermissionChecker()

        checker.setStatus(CameraKPermission.MICROPHONE, PermissionStatus.PermanentlyDenied)

        assertEquals(PermissionStatus.Granted, checker.status(CameraKPermission.CAMERA))
        assertEquals(PermissionStatus.PermanentlyDenied, checker.status(CameraKPermission.MICROPHONE))
        assertEquals(PermissionStatus.Granted, checker.status(CameraKPermission.GALLERY_ADD))
    }

    @Test
    fun opensAtGrantedOnlyUnlessNotDeterminedIsAllowed() {
        val checker = FakePermissionChecker(initial = PermissionStatus.NotDetermined)

        assertEquals(PermissionStatus.NotDetermined, checker.status(CameraKPermission.CAMERA))
        assertTrue(checker.allowsOpen(PermissionStatus.Granted))
        assertFalse(checker.allowsOpen(PermissionStatus.NotDetermined))

        checker.allowsOpenWhenNotDetermined = true
        assertTrue(checker.allowsOpen(PermissionStatus.NotDetermined))
        assertFalse(checker.allowsOpen(PermissionStatus.Denied(shouldShowRationale = true)))
    }
}
