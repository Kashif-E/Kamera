package com.kashif.cameraK.state

import com.kashif.cameraK.utils.CameraKEngineApi
import dev.drewhamilton.poko.Poko

/** The zoom the camera reports: the current ratio and the range it allows. Compares by value. */
@Poko
public class ZoomState @CameraKEngineApi constructor(
    public val ratio: Float,
    public val minRatio: Float,
    public val maxRatio: Float,
)
