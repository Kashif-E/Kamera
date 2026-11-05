package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger

/**
 * What one grab loop reports, on its producer thread. The loop calls each member inside its own
 * callback boundary: a member that throws is logged and that one event is dropped, so nothing a
 * listener does crosses the producer thread (§2.3 rule 4).
 */
internal interface GrabLoopListener {
    /** The grabber started; [relaxed] when it opened only at the camera's own mode (§4.1 row 3). */
    fun onOpened(relaxed: Boolean) = Unit

    /**
     * A converted frame from the producer's pool, which the producer holds a lease on while this
     * runs: valid until the next two frames are published, unless the listener [LeasedFrame.retain]s it.
     */
    fun onFrame(frame: LeasedFrame)

    /** The run ended on its own; [opened] says whether the grabber had started. */
    fun onFailure(failure: Throwable, opened: Boolean)

    /** Stopping or releasing the grabber at the end of the run failed; the run has ended anyway. */
    fun onReleaseFailure(failure: Throwable) {
        CameraKLogger.e("CameraK", "Releasing the camera's grabber failed", failure)
    }
}
