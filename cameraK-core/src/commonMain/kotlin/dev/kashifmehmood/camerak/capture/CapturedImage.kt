package dev.kashifmehmood.camerak.capture

import dev.drewhamilton.poko.Poko
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/**
 * A captured still: a real file in app-owned storage, never a gallery entry. Compares by value.
 *
 * Publishing it to the photo gallery is the media saver plugin's job.
 */
@Poko
public class CapturedImage @CameraKEngineApi constructor(
    public val path: String,
    public val format: ImageFormat,
    public val width: Int,
    public val height: Int,
)
