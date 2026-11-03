package com.kashif.cameraK.engine

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.utils.CameraKEngineApi
import dev.drewhamilton.poko.Poko

/**
 * Closed hierarchy: the engine's own lifecycle. Idle and the holder's terminal Closed are holder
 * concepts; the holder maps these values to its own state. Subtypes compare by value.
 */
@CameraKEngineApi
public sealed interface EngineState {
    /** Not open: before the first start, after stop, after a cancelled or timed-out start. */
    public data object Closed : EngineState

    /** Reported only inside [CameraEngine.start]. */
    public data object Opening : EngineState

    /** The platform confirmed the session is streaming. */
    public data object Open : EngineState

    /** The platform accepted the session and holds it; it resumes on its own. */
    @Poko
    public class Interrupted(public val reason: InterruptionReason) : EngineState

    /** The session ended on its own or never opened. Ends its generation (see [CameraEngine]). */
    @Poko
    public class Failed(public val error: CameraKError) : EngineState
}
