package dev.kashifmehmood.camerak.testing

import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.CameraKPermissionChecker
import dev.kashifmehmood.camerak.permissions.PermissionStatus
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

    /**
     * Whether an open at NotDetermined shows a prompt, when [allowsOpenWhenNotDetermined] is set: a Mac
     * that read its authorization. False stands for a Mac that could not read it, where no prompt is up.
     */
    public var promptsWhenNotDetermined: Boolean = true

    public fun setStatus(permission: CameraKPermission, status: PermissionStatus) {
        statuses.update { it + (permission to status) }
    }

    override fun status(permission: CameraKPermission): PermissionStatus = statuses.value[permission] ?: initial

    override fun allowsOpen(status: PermissionStatus): Boolean = status == PermissionStatus.Granted ||
        (allowsOpenWhenNotDetermined && status == PermissionStatus.NotDetermined)

    override fun promptsOnOpen(permission: CameraKPermission, status: PermissionStatus): Boolean =
        allowsOpenWhenNotDetermined && promptsWhenNotDetermined && status == PermissionStatus.NotDetermined
}
