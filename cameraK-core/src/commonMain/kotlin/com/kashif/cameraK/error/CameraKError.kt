package com.kashif.cameraK.error

import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus

/**
 * Closed hierarchy: an expected failure of a CameraK operation. New subtypes come only in a major
 * release, so an exhaustive `when` over it is safe.
 *
 * Errors are values, not exceptions. Their constructors are public so fakes in other modules can
 * build them; messages are not API.
 *
 * @property cause The platform throwable behind the error, kept as it was thrown. Nothing shown to a
 * user is built from its message.
 */
public sealed class CameraKError(cause: Throwable?) {
    public val cause: Throwable? = null

    /** Whether trying the same operation again can succeed without the user changing anything. */
    public abstract val isRetryable: Boolean

    /** [status] is NotDetermined, Denied, PermanentlyDenied or Restricted. */
    public class Permission(
        public val permission: CameraKPermission,
        public val status: PermissionStatus,
        cause: Throwable? = null,
    ) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")

        /** Only the system settings can grant it now. */
        public val needsSettings: Boolean get() = TODO("S1' stub")
    }

    public class CameraInUse(cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    /** Device policy or Do Not Disturb turned the camera off. */
    public class CameraDisabled(cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class NoCamera(public val lens: CameraLens?, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Disconnected(cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Storage(public val kind: StorageFailure, keptFilePath: String? = null, cause: Throwable? = null) :
        CameraKError(cause) {
        public val keptFilePath: String? = null
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Unsupported(public val feature: CameraFeature, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class NativeLibraryMissing(public val platform: String, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Hardware(public val platformCode: Int?, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Timeout(public val operation: CameraOperation, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Busy(public val operation: CameraOperation) : CameraKError(null) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class InvalidState(public val operation: CameraOperation) : CameraKError(null) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public data object NotReady : CameraKError(null) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public data object Closed : CameraKError(null) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }

    public class Internal(cause: Throwable?) : CameraKError(cause) {
        override val isRetryable: Boolean get() = TODO("S1' stub")
    }
}
