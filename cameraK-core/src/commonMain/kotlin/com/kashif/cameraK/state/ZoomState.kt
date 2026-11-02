package com.kashif.cameraK.state

import com.kashif.cameraK.utils.CameraKEngineApi

/** The zoom the camera reports: the current ratio and the range it allows. Compares by value. */
public class ZoomState @CameraKEngineApi constructor(
    public val ratio: Float,
    public val minRatio: Float,
    public val maxRatio: Float,
)
