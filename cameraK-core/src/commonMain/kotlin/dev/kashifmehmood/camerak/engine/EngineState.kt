package dev.kashifmehmood.camerak.engine

import dev.drewhamilton.poko.Poko
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.InterruptionReason
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

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
