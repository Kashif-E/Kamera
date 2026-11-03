package com.kashif.cameraK.engine

import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.StateFlow
import java.awt.image.BufferedImage

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /** The latest frame; null before the first frame of a session and after stop. */
    public val frames: StateFlow<PreviewFrame?>
}

/**
 * One desktop preview frame. [image] is an opaque `TYPE_3BYTE_BGR` image from the producer's reused
 * ring: valid until the next frame is published, so a consumer copies it and never keeps it. It has
 * no `equals`, so a StateFlow never conflates two frames.
 */
@CameraKEngineApi
public class PreviewFrame(public val image: BufferedImage, public val timestampNanos: Long)
