package com.kashif.cameraK.permissions

import com.kashif.cameraK.error.CameraKError
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionStatusTest {
    @Test
    fun restrictedIsTheOnlyNonRetryablePermissionStatus() {
        val statuses = listOf(
            PermissionStatus.Granted,
            PermissionStatus.Limited,
            PermissionStatus.Denied(shouldShowRationale = true),
            PermissionStatus.Denied(shouldShowRationale = false),
            PermissionStatus.PermanentlyDenied,
            PermissionStatus.Restricted,
            PermissionStatus.NotDetermined,
        )
        val nonRetryable = statuses.filterNot { CameraKError.Permission(CameraKPermission.CAMERA, it).isRetryable }
        assertEquals(listOf<PermissionStatus>(PermissionStatus.Restricted), nonRetryable)
    }

    @Test
    fun deniedComparesByValue() {
        assertEquals<PermissionStatus>(PermissionStatus.Denied(true), PermissionStatus.Denied(true))
        assertEquals(PermissionStatus.Denied(false).hashCode(), PermissionStatus.Denied(false).hashCode())
    }
}
