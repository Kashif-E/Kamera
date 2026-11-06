package com.kashif.cameraK.state

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult

/**
 * An unsolicited notice from the camera, such as a plugin that failed. Events are lossy and not
 * replayed; the authoritative value is always a state flow, and a command's outcome is its result,
 * never an event.
 *
 * Open: new event classes can be added in a minor release, so a `when` over an event needs an
 * `else` branch. Only CameraK creates events.
 *
 * @example
 * ```kotlin
 * LaunchedEffect(holder) {
 *     holder.events.collect { event ->
 *         when (event) {
 *             is CameraKEvent.PluginFailed -> showPluginError(event.pluginId)
 *             else -> Unit
 *         }
 *     }
 * }
 * ```
 */
abstract class CameraKEvent internal constructor() {
    /**
     * A recording the caller did not stop ended: a duration or size limit, an interruption, a
     * platform error, a host stop, or [CameraKStateHolder.close] (SHUTDOWN). The same outcome is in
     * `recordingState` as Finished, with the same [recordingId]; a stop the caller asked for returns
     * its outcome from `stopRecording` instead.
     *
     * @property recordingId The id of the recording that ended, as in `RecordingState.Recording`.
     * @property result The finished file, or why there is none.
     * @property reason Why it ended.
     */
    class RecordingEnded internal constructor(
        val recordingId: Long,
        val result: CameraKResult<RecordedVideo>,
        val reason: RecordingEndReason,
    ) : CameraKEvent() {
        override fun toString(): String = "RecordingEnded(recordingId=$recordingId, reason=$reason)"
    }

    /**
     * A plugin failed. The failure is contained: the camera and the other plugins keep running. A
     * thrown exception arrives as [CameraKError.Internal] with the throwable as its cause, whose
     * message may contain paths or URIs.
     *
     * @property pluginId Which plugin failed: its [com.kashif.cameraK.plugin.CameraKPlugin.id], by
     *   default the plugin class's simple name.
     * @property error What failed.
     */
    class PluginFailed internal constructor(val pluginId: String, val error: CameraKError) : CameraKEvent() {
        override fun toString(): String = "PluginFailed(pluginId=$pluginId, error=${error::class.simpleName})"
    }
}
