package com.kashif.cameraK.testing

import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.permissions.PermissionStatus
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * A permission checker whose statuses the test sets. Every permission starts at [initial]; the
 * holder may open the camera at Granted only, as on Android and iOS, unless
 * [allowsOpenWhenNotDetermined] is set, as on desktop, where macOS prompts on open.
 */
public class FakePermissionChecker(private val initial: PermissionStatus = PermissionStatus.Granted) :
    CameraKPermissionChecker {
    private val statuses = atomic(emptyMap<CameraKPermission, PermissionStatus>())

    public var allowsOpenWhenNotDetermined: Boolean = false

    public fun setStatus(permission: CameraKPermission, status: PermissionStatus) {
        statuses.update { it + (permission to status) }
    }

    override fun status(permission: CameraKPermission): PermissionStatus = statuses.value[permission] ?: initial

    override fun allowsOpen(status: PermissionStatus): Boolean = status == PermissionStatus.Granted ||
        (allowsOpenWhenNotDetermined && status == PermissionStatus.NotDetermined)
}
