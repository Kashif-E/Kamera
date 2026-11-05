package com.kashif.cameraK.engine

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /**
     * Attaches [provider] as the preview's surface. The ViewPort follows [display]; null falls back
     * to the default display. Closing the attachment clears the surface provider only while this
     * attachment is still the current one, so a crossfade never blanks the incoming preview.
     */
    public fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment
}

/** One preview attachment; closing it detaches the surface provider if it is still the current one. */
@CameraKEngineApi
public interface PreviewAttachment : AutoCloseable

/** A preview source that crops its preview to one aspect ratio; CameraK's Android engine implements it. */
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
