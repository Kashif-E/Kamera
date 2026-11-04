package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import org.bytedeco.javacv.FrameGrabber

/**
 * The desktop error mapper (design §4.1): an ordered table over a JavaCV failure's cause chain, the
 * first matching row winning. Row 2 (no device for the selection) and rows 7 to 9 (a grab failure
 * after Open, capture and recording I/O) are decided by the engine, not here.
 */
internal object JavaCvErrorMapper {
    /**
     * Row 1: a missing bytedeco native, in any of the three shapes it arrives in: a LinkageError
     * (NoClassDefFoundError, UnsatisfiedLinkError), CameraGrabber's wrapper around one, or JavaCV's
     * own `FrameGrabber.Exception("Failed to load …")` from `tryLoad`.
     */
    fun isNativeLibraryMissing(failure: Throwable): Boolean = failure.causes().any {
        it is LinkageError || it is FrameGrabber.Exception && it.message.orEmpty().startsWith(FAILED_TO_LOAD)
    }

    /**
     * Rows 1 and 4 to 6, for a grabber that would not open or failed before its first frame, after
     * the retry of row 3. [cameraStatus] is read only for a failure on macOS, where nothing JavaCV
     * reports tells a TCC denial apart (Decision 15).
     */
    fun openFailure(failure: Throwable, os: OperatingSystem, cameraStatus: () -> PermissionStatus): CameraKError {
        if (isNativeLibraryMissing(failure)) {
            return CameraKError.NativeLibraryMissing(nativePlatform(), failure)
        }
        val code = platformCode(failure)
        if (code == EBUSY) return CameraKError.CameraInUse(failure)
        if (os == OperatingSystem.MAC) {
            val denied = when (cameraStatus()) {
                PermissionStatus.PermanentlyDenied, is PermissionStatus.Denied -> PermissionStatus.PermanentlyDenied
                PermissionStatus.Restricted -> PermissionStatus.Restricted
                else -> null
            }
            if (denied != null) return CameraKError.Permission(CameraKPermission.CAMERA, denied, failure)
        }
        return CameraKError.Hardware(code, failure)
    }

    /**
     * Rows 8 and 9 for a recording the recorder ended with [failure]: null when the file at [path] is
     * playable (the recording is `Success(partial)`), else the error. A full disk ([diskFull], or
     * FFmpeg's ENOSPC) is `Storage(FULL)` either way, keeping [path] when the file is playable.
     */
    fun recordingFailure(failure: Throwable, playable: Boolean, path: String, diskFull: Boolean): CameraKError? =
        TODO("D3'")

    /** Row 8 for a still that could not be written: `Storage(NOT_WRITABLE)`, or `FULL` when [diskFull]. */
    fun captureFailure(failure: Throwable, diskFull: Boolean): CameraKError = TODO("D3'")

    /** The code in FFmpeg's `avformat_open_input() error <code>` message anywhere in the cause chain, or null. */
    fun platformCode(failure: Throwable): Int? = failure.causes().firstNotNullOfOrNull { cause ->
        OPEN_ERROR.find(cause.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The bytedeco natives' platform name for [osName] and [osArch], e.g. `macosx-arm64`, as their jars name it. */
    fun nativePlatform(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String {
        val name = osName.lowercase()
        val os = when {
            name.contains("mac") || name.contains("darwin") -> "macosx"
            name.contains("windows") -> "windows"
            else -> "linux"
        }
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64", "x86-64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            else -> osArch.lowercase()
        }
        return "$os-$arch"
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { c -> c !== it } }

    // V4L2's EBUSY, as FFmpeg reports it: AVERROR(EBUSY).
    private const val EBUSY = -16
    private const val FAILED_TO_LOAD = "Failed to load"
    private val OPEN_ERROR = Regex("""avformat_open_input\(\) error (-?\d+)""")
}
