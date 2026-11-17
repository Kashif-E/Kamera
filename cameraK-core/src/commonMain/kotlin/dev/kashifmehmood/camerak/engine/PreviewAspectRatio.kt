package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

/** A preview source that crops its preview to one aspect ratio; CameraK's Android and iOS engines implement it. */
internal interface AspectRatioPreview {
    val aspectRatio: AspectRatio
}

/**
 * The aspect ratio CameraK's engine crops its preview to, which `:cameraK`'s preview lays the
 * viewfinder and its overlay out in (w7 decision G9); null for any other source, the fakes included,
 * whose preview fills its bounds.
 */
@InternalCameraKApi
@CameraKEngineApi
public fun PlatformPreviewSource.previewAspectRatio(): AspectRatio? = (this as? AspectRatioPreview)?.aspectRatio
