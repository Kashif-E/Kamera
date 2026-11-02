package com.kashif.cameraK.recording

/**
 * Closed: why a recording ended. A new platform end cause maps to [INTERRUPTED] or [ERROR]; a new
 * entry waits for a major release.
 */
public enum class RecordingEndReason {
    /** The app called stopRecording. */
    USER,

    /** VideoConfiguration's duration limit was reached. */
    MAX_DURATION,

    /** VideoConfiguration's file-size limit was reached. */
    MAX_FILE_SIZE,

    /** The camera or the session was cut off, or the host stopped the camera. */
    INTERRUPTED,

    /** A platform or encoder error ended it. */
    ERROR,

    /** The holder was closed while recording. */
    SHUTDOWN,
}
