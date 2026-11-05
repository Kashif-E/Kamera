package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.VideoRotation
import com.kashif.cameraK.controller.toVideoOrientation
import com.kashif.cameraK.controller.turn
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRect
import platform.UIKit.UIInterfaceOrientation

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /** A preview layer bound to the session the engine created at construction. */
    public fun createPreviewLayer(): AVCaptureVideoPreviewLayer
}

/** How a source lays out the layers it made; CameraK's iOS engine implements it. */
@OptIn(ExperimentalForeignApi::class)
internal interface PreviewLayerLayout {
    fun layOut(layer: AVCaptureVideoPreviewLayer, bounds: CValue<CGRect>, interfaceOrientation: UIInterfaceOrientation?)
}

/**
 * Lays out [layer], one [PlatformPreviewSource.createPreviewLayer] made, in a host view whose bounds
 * are [bounds] and whose window scene shows the interface in [interfaceOrientation] (null before it
 * has one). `:cameraK`'s preview host calls it from each layout pass, on the main thread. CameraK's
 * engine fills a centred box of the configured aspect ratio and turns the layer (w6 decision G1,
 * §2.4); any other source's layer fills the bounds and turns to the interface.
 */
@InternalCameraKApi
@CameraKEngineApi
@OptIn(ExperimentalForeignApi::class)
public fun PlatformPreviewSource.layOutPreviewLayer(
    layer: AVCaptureVideoPreviewLayer,
    bounds: CValue<CGRect>,
    interfaceOrientation: UIInterfaceOrientation?,
) {
    if (this is PreviewLayerLayout) {
        layOut(layer, bounds, interfaceOrientation)
        return
    }
    layer.setFrame(bounds)
    val orientation = interfaceOrientation?.toVideoOrientation() ?: return
    layer.connection?.turn(VideoRotation.Orientation(orientation))
}
