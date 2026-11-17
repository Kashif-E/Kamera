package dev.kashifmehmood.camerak.state

import dev.drewhamilton.poko.Poko
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/** The zoom the camera reports: the current ratio and the range it allows. Compares by value. */
@Poko
public class ZoomState @CameraKEngineApi constructor(
    public val ratio: Float,
    public val minRatio: Float,
    public val maxRatio: Float,
)
