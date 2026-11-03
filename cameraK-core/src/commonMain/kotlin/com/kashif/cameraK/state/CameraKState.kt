package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.engine.LegacyControllerAccess
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
 *     is CameraKState.Ready, is CameraKState.Interrupted -> CameraControls(holder)
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

    /**
     * The platform confirmed the camera is open and streaming. A holder publishes one Ready value
     * every time the camera reaches Ready.
     */
    public class Ready internal constructor(private val legacy: LegacyControllerAccess?) : CameraKState {
        /**
         * The 1.x controller over this holder's engine, read when called. It throws over an engine
         * that has none, such as camerak-testing's FakeCameraEngine.
         */
        @Deprecated("Issue commands on CameraKStateHolder. The 1.x controller is removed in CameraK 2.0.")
        public val controller: CameraController
            get() = legacy?.legacyController
                ?: throw IllegalStateException("This holder's engine has no 1.x controller")

        override fun equals(other: Any?): Boolean = other is Ready && other.legacy === legacy

        override fun hashCode(): Int = legacy?.hashCode() ?: 0

        override fun toString(): String = "Ready"
    }

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
