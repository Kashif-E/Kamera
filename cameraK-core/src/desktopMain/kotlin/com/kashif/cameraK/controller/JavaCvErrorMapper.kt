package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.PermissionStatus

/** The desktop error mapper (design §4.1): an ordered table over a JavaCV failure's cause chain. */
internal object JavaCvErrorMapper {
    /** Row 1: a missing bytedeco native, in any of the three shapes JavaCV and CameraGrabber give it. */
    fun isNativeLibraryMissing(failure: Throwable): Boolean = TODO("D1'")

    /** Rows 1 and 4 to 6, for a grabber that would not open or failed before its first frame. */
    fun openFailure(failure: Throwable, os: OperatingSystem, cameraStatus: () -> PermissionStatus): CameraKError =
        TODO("D1'")

    /** The code in FFmpeg's `avformat_open_input() error <code>` message, or null. */
    fun platformCode(failure: Throwable): Int? = TODO("D1'")

    /** The bytedeco natives' platform name for [osName] and [osArch], e.g. `macosx-arm64`. */
    fun nativePlatform(osName: String, osArch: String): String = TODO("D1'")
}
