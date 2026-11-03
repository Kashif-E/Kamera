package com.kashif.cameraK.testing

import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.permissions.PermissionStatus

/** S2' red: the declaration only. */
public class FakePermissionChecker(private val initial: PermissionStatus = PermissionStatus.Granted) :
    CameraKPermissionChecker {
    public var allowsOpenWhenNotDetermined: Boolean
        get() = TODO("S2' red")
        set(value) = TODO("S2' red $value")

    public fun setStatus(permission: CameraKPermission, status: PermissionStatus): Unit =
        TODO("S2' red $permission $status")

    override fun status(permission: CameraKPermission): PermissionStatus = TODO("S2' red $permission $initial")
}
