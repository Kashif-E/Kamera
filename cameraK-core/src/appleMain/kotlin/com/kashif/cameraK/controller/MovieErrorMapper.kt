package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.utils.CameraKLogger
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorMaximumDurationReached
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.AVFoundation.AVErrorSessionConfigurationChanged
import platform.AVFoundation.AVErrorSessionWasInterrupted
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSNumber

/** How a movie ended: why, and the error its result carries (null for a success). */
internal class MovieEnd(val reason: RecordingEndReason, val error: CameraKError?)

/**
 * Design §4.1, iOS, a recording's end: what AVFoundation's didFinishRecording [error] (null for none)
 * means for a movie whose end the app requested with [requested] (null when the platform ended it),
 * whose file is at [path].
 *
 * Every row follows the partial-file rule of §5 on AVFoundation's playable verdict,
 * [AVErrorRecordingSuccessfullyFinishedKey] (or no error): a playable file is a success and the reason
 * says why the recording ended; an unplayable one is Hardware with AVFoundation's code, the NSError as
 * its cause. A full disk is the one failure with a file: Storage(FULL), keeping the playable file's
 * path. No error at all ends for the reason requested, or INTERRUPTED when nobody asked.
 */
internal fun movieEnd(error: NSError?, requested: RecordingEndReason?, path: String): MovieEnd {
    if (error == null) return MovieEnd(requested ?: RecordingEndReason.INTERRUPTED, null)
    val playable = error.recordedPlayably
    val code = if (error.domain == AVFoundationErrorDomain) error.code else null
    val reason = when (code) {
        AVErrorMaximumDurationReached -> RecordingEndReason.MAX_DURATION
        AVErrorMaximumFileSizeReached -> RecordingEndReason.MAX_FILE_SIZE
        AVErrorSessionWasInterrupted, AVErrorMediaServicesWereReset -> RecordingEndReason.INTERRUPTED
        AVErrorSessionConfigurationChanged -> {
            // The topology freeze (§8) keeps the session's outputs fixed while recording, so this is a bug.
            CameraKLogger.e(
                "CameraK",
                "CameraK bug: the session's configuration changed during a recording",
                NSErrorException(error),
            )
            RecordingEndReason.INTERRUPTED
        }
        else -> RecordingEndReason.ERROR
    }
    if (code == AVErrorDiskFull) {
        val full = CameraKError.Storage(StorageFailure.FULL, path.takeIf { playable }, NSErrorException(error))
        return MovieEnd(RecordingEndReason.ERROR, full)
    }
    if (playable) return MovieEnd(reason, null)
    return MovieEnd(reason, CameraKError.Hardware(error.code.toInt(), NSErrorException(error)))
}

/** AVFoundation's verdict that the file it finished is playable, despite the error. */
internal val NSError.recordedPlayably: Boolean
    get() = when (val finished = userInfo[AVErrorRecordingSuccessfullyFinishedKey]) {
        is NSNumber -> finished.boolValue
        is Boolean -> finished
        else -> false
    }
