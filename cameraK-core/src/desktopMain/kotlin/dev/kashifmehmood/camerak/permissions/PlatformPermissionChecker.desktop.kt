package dev.kashifmehmood.camerak.permissions

import dev.kashifmehmood.camerak.controller.MacCameraAuthorization
import dev.kashifmehmood.camerak.controller.MacMediaType
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi
import java.awt.EventQueue
import kotlin.concurrent.thread

internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    desktopPermissionChecker

/** macOS's authorization of one permission. Tests fake this, never the Objective-C runtime under it. */
internal fun interface MacAuthorizationRead {
    fun status(permission: CameraKPermission): PermissionStatus

    /** Whether macOS could be asked for [permission]'s authorization: a NotDetermined then means the prompt is still to come. */
    fun readable(permission: CameraKPermission): Boolean = true

    /** Asks macOS for [permission], showing its prompt, and returns the status after the answer. */
    fun request(permission: CameraKPermission): PermissionStatus = status(permission)
}

/**
 * macOS's authorization read over D1''s JNA seam, which owns the status mapping and the JNA-absent
 * rule. [read] is the status of one AVFoundation media type, null when it cannot be read,
 * [MacCameraAuthorization.read] in production.
 */
internal class JnaMacAuthorizationRead(
    private val ask: (MacMediaType) -> PermissionStatus? = { MacCameraAuthorization().requestAccess(it) },
    private val read: (MacMediaType) -> PermissionStatus? = MacCameraAuthorization()::read,
) : MacAuthorizationRead {
    override fun status(permission: CameraKPermission): PermissionStatus =
        macMediaTypeOf(permission)?.let { read(it) ?: PermissionStatus.NotDetermined } ?: PermissionStatus.Granted

    override fun readable(permission: CameraKPermission): Boolean =
        macMediaTypeOf(permission)?.let { read(it) != null } ?: true

    override fun request(permission: CameraKPermission): PermissionStatus =
        macMediaTypeOf(permission)?.let { ask(it) ?: PermissionStatus.NotDetermined } ?: PermissionStatus.Granted
}

/** The AVFoundation media type whose authorization is [permission]'s on macOS; null for the gallery, a folder. */
internal fun macMediaTypeOf(permission: CameraKPermission): MacMediaType? = when (permission) {
    CameraKPermission.CAMERA -> MacMediaType.VIDEO
    CameraKPermission.MICROPHONE -> MacMediaType.AUDIO
    CameraKPermission.GALLERY_ADD -> null
}

/**
 * The desktop checker (Decision 15). Linux and Windows have no camera permission, so every status is
 * Granted. macOS reads its authorization through [macRead], except the gallery, which is a folder.
 * The camera may open at NotDetermined, because AVFoundation shows macOS's prompt when it opens.
 */
internal class DesktopPermissionChecker(osName: String, private val macRead: MacAuthorizationRead) :
    CameraKPermissionChecker {
    private val isMac = osName.lowercase().contains("mac")

    override fun status(permission: CameraKPermission): PermissionStatus = if (isMac &&
        permission != CameraKPermission.GALLERY_ADD
    ) {
        macRead.status(permission)
    } else {
        PermissionStatus.Granted
    }

    override fun allowsOpen(status: PermissionStatus): Boolean =
        status == PermissionStatus.Granted || status == PermissionStatus.NotDetermined

    // macOS's prompt is up only for a NotDetermined it reported; without JNA or AVFoundation nothing was asked.
    override fun promptsOnOpen(permission: CameraKPermission, status: PermissionStatus): Boolean = isMac &&
        permission != CameraKPermission.GALLERY_ADD &&
        status == PermissionStatus.NotDetermined &&
        macRead.readable(permission)

    /**
     * The status of [permission] after asking for it: macOS's prompt shows, through AVFoundation, at a
     * NotDetermined it could read; anything else is [status] as it is. Blocks until the answer.
     */
    fun request(permission: CameraKPermission): PermissionStatus {
        val status = status(permission)
        return if (promptsOnOpen(permission, status)) macRead.request(permission) else status
    }

    /**
     * What refuses opening [permission]'s device, after asking for it as [request] does; null when it
     * may open.
     */
    fun requestToOpen(permission: CameraKPermission): PermissionStatus? {
        val status = status(permission)
        val asked = promptsOnOpen(permission, status)
        return when (val answer = if (asked) macRead.request(permission) else status) {
            PermissionStatus.Granted, PermissionStatus.Limited -> null
            // Asked and left unanswered, or not asked for want of a usage key: the open would wait on
            // macOS's prompt with no deadline, or end the app. Nothing asked (no JNA): the open asks.
            PermissionStatus.NotDetermined -> if (asked) answer else null
            else -> answer
        }
    }
}

// The one desktop checker; tests replace it with one over a fake OS name and authorization read.
@Volatile
internal var desktopPermissionChecker: DesktopPermissionChecker =
    DesktopPermissionChecker(System.getProperty("os.name").orEmpty(), JnaMacAuthorizationRead())

/**
 * The desktop status of [permission]: Granted on Linux and Windows, macOS's authorization on macOS.
 * The holder's checker and `:cameraK`'s permission state both read this.
 */
@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus =
    desktopPermissionChecker.status(permission)

/**
 * Whether [permission] reads NotDetermined on a Mac that could read it, where macOS's prompt may be
 * up now or come with the device's open, and its answer comes outside the app's lifecycle.
 * `:cameraK`'s permission state reads the statuses again while this holds.
 */
@InternalCameraKApi
public fun platformPermissionPromptPending(context: CameraKPlatformContext, permission: CameraKPermission): Boolean =
    desktopPermissionChecker.let { it.promptsOnOpen(permission, it.status(permission)) }

/**
 * Asks for [permissions] off the AWT event thread, then calls [onResult] with their statuses on it,
 * Compose Desktop's Main. On macOS each camera or microphone permission at NotDetermined is asked
 * through AVFoundation, which shows the prompt, and waits for the answer; Linux and Windows have no
 * permission to ask for. A macOS PermanentlyDenied can only be changed in System Settings, so the
 * app has to send the user there.
 */
@InternalCameraKApi
public fun requestPlatformPermissions(
    context: CameraKPlatformContext,
    permissions: List<CameraKPermission>,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
) {
    thread(isDaemon = true, name = "CameraK permission request") {
        val statuses = permissions.associateWith { desktopPermissionChecker.request(it) }
        EventQueue.invokeLater { onResult(statuses) }
    }
}
