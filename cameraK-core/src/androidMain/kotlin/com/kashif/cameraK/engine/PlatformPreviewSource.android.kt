package com.kashif.cameraK.engine

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.utils.CameraKEngineApi

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /**
     * Attaches [provider] as the preview's surface. The ViewPort follows [display]; null falls back
     * to the default display. Closing the attachment takes [provider] away only while this attachment
     * is still the current one, so a crossfade never blanks the incoming preview; with no preview
     * attached the camera keeps streaming, into a surface that drops the frames.
     */
    public fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment
}

/** One preview attachment; closing it detaches the surface provider if it is still the current one. */
@CameraKEngineApi
public interface PreviewAttachment : AutoCloseable
