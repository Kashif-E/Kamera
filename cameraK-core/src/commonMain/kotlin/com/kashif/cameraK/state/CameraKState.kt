package com.kashif.cameraK.state

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import dev.drewhamilton.poko.Poko

/**
 * The camera's lifecycle, as [CameraKStateHolder.cameraState] publishes it. Closed hierarchy: new
 * subtypes come only in a major release, so an exhaustive `when` over it is safe.
 *
 * @example
 * ```kotlin
 * when (val state = holder.cameraState.collectAsState().value) {
 *     CameraKState.Idle, CameraKState.Starting -> LoadingIndicator()
 *     CameraKState.Ready, is CameraKState.Interrupted -> CameraControls(holder)
 *     is CameraKState.Error -> ErrorMessage(state.error, onRetry = { holder.retry() })
 *     CameraKState.Closed -> Unit
 * }
 * ```
 */
public sealed interface CameraKState {
    /** Not started, or stopped because the host left STARTED. The camera is closed. */
    public data object Idle : CameraKState

    /** A start is in progress: the permission check, then the platform opening the camera. */
    public data object Starting : CameraKState

    /** The platform confirmed the camera is open and streaming. */
    public data object Ready : CameraKState

    /**
     * The platform accepted the session but is holding it and resumes it on its own: a phone call,
     * multitasking, another app holding the camera for now. Entered from Starting as well as from
     * Ready; the holder returns to Ready when the platform resumes. Compares by value.
     */
    @Poko
    public class Interrupted(public val reason: InterruptionReason) : CameraKState

    /**
     * A start failed or the session died. `retry()` restarts it when [isRetryable]. An
     * `Error(Permission)` whose status is not Restricted is left on its own when the host resumes
     * and the permission is now granted. Compares by value.
     */
    @Poko
    public class Error(public val error: CameraKError) : CameraKState {
        public val isRetryable: Boolean get() = error.isRetryable
    }

    /** Terminal. Entered by `close()`; nothing leaves it. */
    public data object Closed : CameraKState
}
