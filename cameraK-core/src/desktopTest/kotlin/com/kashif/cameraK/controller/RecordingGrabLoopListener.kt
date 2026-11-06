package com.kashif.cameraK.controller

import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A grab loop's listener for tests that drive [CameraGrabber.startRun] directly: it records each
 * published frame's image and every failure, and runs [onImage] on the producer thread as each frame
 * is published, while the frame is still the latest.
 */
internal class RecordingGrabLoopListener(private val onImage: (BufferedImage) -> Unit = {}) : GrabLoopListener {
    val images = CopyOnWriteArrayList<BufferedImage>()

    /** Each [onFailure], with whether the grabber had opened. */
    val failures = CopyOnWriteArrayList<Pair<Throwable, Boolean>>()
    val releaseFailures = CopyOnWriteArrayList<Throwable>()

    override fun onFrame(frame: LeasedFrame) {
        images += frame.image
        onImage(frame.image)
    }

    override fun onFailure(failure: Throwable, opened: Boolean) {
        failures += failure to opened
    }

    override fun onReleaseFailure(failure: Throwable) {
        releaseFailures += failure
    }
}
