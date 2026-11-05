package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.VideoRotation
import com.kashif.cameraK.controller.toVideoOrientation
import com.kashif.cameraK.controller.turn
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRect
import platform.UIKit.UIInterfaceOrientation

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /** A preview layer bound to the session the engine created at construction. */
    public fun createPreviewLayer(): AVCaptureVideoPreviewLayer

    /**
     * Lays out [layer], one [createPreviewLayer] made, in a host view whose bounds are [bounds] and
     * whose window scene shows the interface in [interfaceOrientation] (null before it has one). The
     * host calls it from each layout pass, on the main thread. This default fills the bounds and turns
     * the layer to the interface.
     */
    @OptIn(ExperimentalForeignApi::class)
    public fun layOutPreviewLayer(
        layer: AVCaptureVideoPreviewLayer,
        bounds: CValue<CGRect>,
        interfaceOrientation: UIInterfaceOrientation?,
    ) {
        layer.setFrame(bounds)
        val orientation = interfaceOrientation?.toVideoOrientation() ?: return
        layer.connection?.turn(VideoRotation.Orientation(orientation))
    }
}
