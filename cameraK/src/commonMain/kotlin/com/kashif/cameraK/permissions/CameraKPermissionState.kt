package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The statuses of some [CameraKPermission]s and the request for them, from
 * [rememberCameraKPermissionState].
 *
 * The holder needs no wiring to it: a camera permission granted here opens the camera on the
 * holder's next resume re-check.
 */
@Stable
public interface CameraKPermissionState {
    /** Each permission's status. Snapshot state, read again on every resume and after each request. */
    public val statuses: Map<CameraKPermission, PermissionStatus>

    /** Whether every permission is [PermissionStatus.Granted]. */
    public val allGranted: Boolean

    /**
     * Shows the system prompts for the permissions that can still be asked for, then updates
     * [statuses] and calls the `onResult` given to [rememberCameraKPermissionState], on the main
     * thread. Call it from an event handler, such as a button's `onClick`, never from composition.
     * A [PermissionStatus.PermanentlyDenied] permission gets no prompt: only the system settings can
     * grant it now.
     */
    public fun launchRequest()
}

/**
 * Remembers the statuses of [permissions] and a request for them, read from the same platform
 * rule the holder's permission check uses. Requests are explicit: nothing is asked when this
 * enters the composition.
 *
 * - Android asks with the Activity Result API. A denial after which the activity shows no rationale,
 *   following an earlier request, is [PermissionStatus.PermanentlyDenied]; a first request the user
 *   only dismissed stays [PermissionStatus.Denied].
 * - iOS asks AVFoundation for the camera and the microphone, and Photos for add-only access for
 *   [CameraKPermission.GALLERY_ADD]. iOS prompts once, so its denial is permanent.
 * - Desktop prompts for nothing: Linux and Windows have no camera permission, and macOS asks when
 *   the camera opens. A request re-reads the statuses.
 *
 * @param onResult Called on the main thread with every status after each [CameraKPermissionState.launchRequest].
 *
 * @example
 * ```kotlin
 * val permissions = rememberCameraKPermissionState(CameraKPermission.CAMERA)
 * if (permissions.allGranted) {
 *     CameraKScreen(holder)
 * } else {
 *     Button(onClick = permissions::launchRequest) { Text("Allow camera") }
 * }
 * ```
 */
@Composable
public expect fun rememberCameraKPermissionState(
    vararg permissions: CameraKPermission,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit = {},
): CameraKPermissionState

/** The state each platform's [rememberCameraKPermissionState] returns: [read] gives a status, [request] asks. */
internal class PlatformPermissionState(
    private val permissions: List<CameraKPermission>,
    private val read: (CameraKPermission) -> PermissionStatus,
) : CameraKPermissionState {
    /** Set from composition by the platform's remember; it asks for [permissions] and ends in [publish]. */
    var request: () -> Unit = {}

    override var statuses: Map<CameraKPermission, PermissionStatus> by mutableStateOf(readAll())
        private set

    override val allGranted: Boolean get() = statuses.values.all { it == PermissionStatus.Granted }

    override fun launchRequest() = request()

    /** Reads every status again and publishes it. */
    fun refresh(): Map<CameraKPermission, PermissionStatus> = readAll().also(::publish)

    fun publish(statuses: Map<CameraKPermission, PermissionStatus>) {
        this.statuses = statuses
    }

    private fun readAll(): Map<CameraKPermission, PermissionStatus> = permissions.associateWith(read)
}

/** Reads [state] again on every resume: the user may have answered a prompt or changed a setting meanwhile. */
@Composable
internal fun RefreshOnResume(state: PlatformPermissionState) {
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
}
