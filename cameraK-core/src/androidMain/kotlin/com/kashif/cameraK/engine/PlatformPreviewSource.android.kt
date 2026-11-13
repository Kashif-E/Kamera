package com.kashif.cameraK.engine

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.utils.CameraKEngineApi

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /**
     * Attaches [provider] as the preview's surface; the camera draws into the newest open attachment.
     * The ViewPort follows [display]; null falls back to the default display. Closing the newest gives
     * the preview to the newest still open, and closing an older one changes nothing, so a crossfade
     * never blanks the incoming preview. With no preview attached the camera keeps streaming, into a
     * surface that drops the frames or the frame analysis.
     */
    public fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment
}

/** One preview attachment; closing it hands the preview to the newest attachment still open, if it was the newest. */
@CameraKEngineApi
public interface PreviewAttachment : AutoCloseable
