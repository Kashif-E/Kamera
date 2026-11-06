package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
 *   only dismissed stays [PermissionStatus.Denied]. That record is a preferences file, read off the
 *   main thread, so until the first read finishes (just after the composition first resumes) a
 *   permission that is not granted reads [PermissionStatus.NotDetermined].
 * - iOS asks AVFoundation for the camera and the microphone, and Photos for add-only access for
 *   [CameraKPermission.GALLERY_ADD]. iOS prompts once, so its denial is permanent. Below iOS 14 the
 *   gallery is never asked for and reads [PermissionStatus.NotDetermined]: saving raises the prompt.
 * - Desktop prompts for nothing: Linux and Windows have no camera permission, and macOS asks when
 *   the camera opens. A request re-reads the statuses.
 *
 * @param onResult Called on the main thread with every status after each [CameraKPermissionState.launchRequest].
 *
 * @sample com.kashif.cameraK.samples.RememberCameraKPermissionStateSample
 */
@Composable
public expect fun rememberCameraKPermissionState(
    vararg permissions: CameraKPermission,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit = {},
): CameraKPermissionState

/**
 * The state each platform's [rememberCameraKPermissionState] returns: [read] gives a status and runs
 * in [readContext]; until the first read, [initial] does. [request] asks.
 */
internal class PlatformPermissionState(
    private val permissions: List<CameraKPermission>,
    private val readContext: CoroutineContext = EmptyCoroutineContext,
    initial: ((CameraKPermission) -> PermissionStatus)? = null,
    private val read: (CameraKPermission) -> PermissionStatus,
) : CameraKPermissionState {
    /** Set from composition by the platform's remember; it asks for [permissions] and ends in [publish]. */
    var request: () -> Unit = {}

    override var statuses: Map<CameraKPermission, PermissionStatus> by mutableStateOf(
        permissions.associateWith(initial ?: read),
    )
        private set

    override val allGranted: Boolean get() = statuses.values.all { it == PermissionStatus.Granted }

    override fun launchRequest() = request()

    // One read at a time, in call order, so a slow read never publishes over a later one.
    private val reading = Mutex()

    /** Reads every status again in [readContext], then publishes it in the caller's context. */
    suspend fun refresh(): Map<CameraKPermission, PermissionStatus> = reading.withLock {
        withContext(readContext) { permissions.associateWith(read) }.also(::publish)
    }

    fun publish(statuses: Map<CameraKPermission, PermissionStatus>) {
        this.statuses = statuses
    }
}

/**
 * Reads [state] again on every resume, in [scope]: the user may have answered a prompt or changed a
 * setting meanwhile.
 */
@Composable
internal fun RefreshOnResume(state: PlatformPermissionState, scope: CoroutineScope = rememberCoroutineScope()) {
    LifecycleResumeEffect(state, scope) {
        // Undispatched, so a platform that reads in place has fresh statuses before the next frame.
        val refresh = scope.launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
        onPauseOrDispose { refresh.cancel() }
    }
}
