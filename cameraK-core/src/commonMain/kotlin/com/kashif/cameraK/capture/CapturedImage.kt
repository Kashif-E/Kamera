package com.kashif.cameraK.capture

import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * A captured still: a real file in app-owned storage, never a gallery entry. Compares by value.
 *
 * Publishing it to the photo gallery is the media saver plugin's job.
 */
public class CapturedImage @CameraKEngineApi constructor(
    public val path: String,
    public val format: ImageFormat,
    public val width: Int,
    public val height: Int,
)
