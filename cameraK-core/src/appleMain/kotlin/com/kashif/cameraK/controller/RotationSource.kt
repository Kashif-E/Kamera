package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureVideoPreviewLayer

/**
 * The angles `AVCaptureDevice.RotationCoordinator` gives for one camera and one preview layer, iOS 17
 * and later (design §2.4). Closing it stops reporting preview angles.
 */
internal interface RotationSource : AutoCloseable {
    /** Degrees a photo or movie connection turns to keep the horizon level. */
    val captureAngle: Double

    /** Degrees the preview layer's connection turns to show the scene upright in its interface. */
    val previewAngle: Double
}

/** Makes [RotationSource]s; the engine's seam over the coordinator, which the simulator cannot drive. */
internal fun interface RotationSourceFactory {
    /**
     * The coordinator of [device] and [previewLayer], which calls [onPreviewAngle], from whatever
     * thread reports it, with each new preview angle; null when [device] is not an AVFoundation camera.
     */
    fun make(
        device: CaptureDeviceOps,
        previewLayer: AVCaptureVideoPreviewLayer?,
        onPreviewAngle: (Double) -> Unit,
    ): RotationSource?
}

/** The production factory: AVCaptureDevice.RotationCoordinator over the camera's AVCaptureDevice. */
internal object CoordinatorRotationSources : RotationSourceFactory {
    override fun make(
        device: CaptureDeviceOps,
        previewLayer: AVCaptureVideoPreviewLayer?,
        onPreviewAngle: (Double) -> Unit,
    ): RotationSource? = TODO("I3' rotation coordinator")
}
