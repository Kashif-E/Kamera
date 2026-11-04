package com.kashif.cameraK.plugin

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraUIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What one attachment of a plugin may use. The holder gives each attachment a new context; it holds
 * no commands, so a plugin cannot shut the camera down, record, or write the holder's state.
 */
public interface CameraKPluginContext {
    /**
     * This attachment's own scope: a SupervisorJob child of the holder's scope. A coroutine in it
     * that throws sets [failure] and emits [CameraKEvent.PluginFailed]; the camera and the other
     * plugins carry on. Cancelled right after `onDetach`.
     */
    public val scope: CoroutineScope

    /**
     * Null while this attachment is healthy. The holder, its only writer, sets it once, to the
     * first error: `onAttach` threw, a coroutine in [scope] threw, the plugin called
     * [reportFailure], or a registration made through [frames] came back with a non-null
     * rejection. A new attachment starts with a new context, so null again. A plugin publishes its
     * status as `PluginStatus.Failed(failure)` while this is non-null.
     */
    public val failure: StateFlow<CameraKError?>

    /** The holder's dispatchers. */
    public val dispatchers: CameraKDispatchers

    /** The holder's [com.kashif.cameraK.state.CameraKStateHolder.cameraState]. */
    public val cameraState: StateFlow<CameraKState>

    /** The holder's `uiState`. */
    public val uiState: StateFlow<CameraUIState>

    /** The holder's `capabilities`. */
    public val capabilities: StateFlow<CameraCapabilities>

    /** The holder's `deviceOrientation`. */
    public val deviceOrientation: StateFlow<DeviceOrientation>

    /** The holder's recording state. */
    public val recordingState: StateFlow<RecordingState>

    /**
     * The holder's frame source. A registration whose `rejection` is non-null also sets [failure]
     * and emits [CameraKEvent.PluginFailed]; frames reach the analyzer as from the holder's own.
     */
    public val frames: FrameSource

    /** The holder's captures flow: every successful capture once its file is durable. */
    public val captures: SharedFlow<CapturedImage>

    /**
     * A failure of the plugin's own that is not a thrown exception: it is logged, sets [failure]
     * if this is the attachment's first, and is emitted as [CameraKEvent.PluginFailed].
     */
    public fun reportFailure(error: CameraKError)
}
