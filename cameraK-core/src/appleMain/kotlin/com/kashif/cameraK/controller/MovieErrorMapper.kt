package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.recording.RecordingEndReason
import platform.Foundation.NSError

/** How a movie ended: why, and the error its result carries (null for a success). */
internal class MovieEnd(val reason: RecordingEndReason, val error: CameraKError?)

/**
 * Design §4.1, iOS, a recording's end: what AVFoundation's didFinishRecording [error] (null for none)
 * means for a movie whose end the app requested with [requested] (null when the platform ended it),
 * whose file is at [path].
 */
internal fun movieEnd(error: NSError?, requested: RecordingEndReason?, path: String): MovieEnd =
    TODO("I3': the movie error mapper")
