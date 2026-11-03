@file:OptIn(ExperimentalAtomicApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.permissions.PermissionStatus
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A permission checker whose statuses the test sets. Every permission starts at [initial]; the
 * holder may open the camera at Granted only, as on Android and iOS, unless
 * [allowsOpenWhenNotDetermined] is set, as on desktop, where macOS prompts on open.
 */
public class FakePermissionChecker(private val initial: PermissionStatus = PermissionStatus.Granted) :
    CameraKPermissionChecker {
    private val statuses = AtomicReference(emptyMap<CameraKPermission, PermissionStatus>())

    public var allowsOpenWhenNotDetermined: Boolean = false

    public fun setStatus(permission: CameraKPermission, status: PermissionStatus) {
        while (true) {
            val current = statuses.load()
            if (statuses.compareAndSet(current, current + (permission to status))) return
        }
    }

    override fun status(permission: CameraKPermission): PermissionStatus = statuses.load()[permission] ?: initial

    override fun allowsOpen(status: PermissionStatus): Boolean = status == PermissionStatus.Granted ||
        (allowsOpenWhenNotDetermined && status == PermissionStatus.NotDetermined)
}
