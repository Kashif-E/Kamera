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
public sealed class CameraKError(public val cause: Throwable?) {
    /** Whether trying the same operation again can succeed without the user changing anything. */
    public abstract val isRetryable: Boolean

    /** [status] is NotDetermined, Denied, PermanentlyDenied or Restricted. */
    public class Permission(
        public val permission: CameraKPermission,
        public val status: PermissionStatus,
        cause: Throwable? = null,
    ) : CameraKError(cause) {
        override val isRetryable: Boolean get() = status != PermissionStatus.Restricted

        /** Only the system settings can grant it now. */
        public val needsSettings: Boolean get() = status == PermissionStatus.PermanentlyDenied
    }

    public class CameraInUse(cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = true
    }

    /** Device policy or Do Not Disturb turned the camera off. */
    public class CameraDisabled(cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = false
    }

    public class NoCamera(public val lens: CameraLens?, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = false
    }

    public class Disconnected(cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = true
    }

    /**
     * @property keptFilePath A file the operation left on disk for the app to recover: a recording that
     *   hit a full disk, a capture whose gallery import failed. Null when nothing was kept.
     */
    public class Storage(
        public val kind: StorageFailure,
        public val keptFilePath: String? = null,
        cause: Throwable? = null,
    ) : CameraKError(cause) {
        override val isRetryable: Boolean get() = kind != StorageFailure.FULL
    }

    public class Unsupported(public val feature: CameraFeature, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = false
    }

    /** A native library CameraK needs is missing at run time; [platform] names it, e.g. "macosx-arm64". */
    public class NativeLibraryMissing(public val platform: String, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = false
    }

    public class Hardware(public val platformCode: Int?, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = true
    }

    public class Timeout(public val operation: CameraOperation, cause: Throwable? = null) : CameraKError(cause) {
        override val isRetryable: Boolean get() = true
    }

    public class Busy(public val operation: CameraOperation) : CameraKError(null) {
        override val isRetryable: Boolean get() = true
    }

    /** The operation does not apply now, such as stopping with nothing recording. */
    public class InvalidState(public val operation: CameraOperation) : CameraKError(null) {
        override val isRetryable: Boolean get() = false
    }

    public data object NotReady : CameraKError(null) {
        override val isRetryable: Boolean get() = true
    }

    public data object Closed : CameraKError(null) {
        override val isRetryable: Boolean get() = false
    }

    /** A library bug or an unexpected platform exception. */
    public class Internal(cause: Throwable?) : CameraKError(cause) {
        override val isRetryable: Boolean get() = false
    }
}
