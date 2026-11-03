package com.kashif.cameraK.frames

import com.kashif.cameraK.error.CameraKError
import kotlinx.coroutines.flow.StateFlow

/** The one source of analysis frames. Apps and plugins use the same API, and nobody adds a frame output of their own. */
public interface FrameSource {
    /**
     * Frames reach [analyzer] only while the camera is Ready: on its own serial worker, or on the
     * producer thread when [FrameSpec.synchronous]. Registering or unregistering never rebinds the
     * camera, except the one late-registration bind on Android when the configuration did not ask
     * for frame analysis up front.
     */
    public fun register(spec: FrameSpec = FrameSpec(), analyzer: FrameAnalyzer): FrameRegistration
}

public fun interface FrameAnalyzer {
    /**
     * [frame] is borrowed: valid only until this call returns, unless retained. Exceptions are
     * caught, logged with the throwable and counted against the registration; the frame is still
     * released.
     */
    public fun analyze(frame: CameraFrame)
}

/** One analyzer's registration. [close] unregisters it and is idempotent. */
public interface FrameRegistration : AutoCloseable {
    /**
     * False while the registration waits for a frame output (the Android late bind, the iOS
     * topology freeze during a recording). The plugin lifecycle shows it as Paused.
     */
    public val active: StateFlow<Boolean>

    /**
     * Non-null when this registration will never receive frames: `Unsupported(ANALYSIS_FORMAT)`
     * when the session's format is not in [FrameSpec.acceptedFormats]. [active] then stays false.
     */
    public val rejection: CameraKError?
}
