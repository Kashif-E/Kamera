package com.kashif.cameraK.testing

import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec

/** S2' red: the declaration only. */
public class FakeFrameSource internal constructor(
    internal val sessionFormat: () -> FrameFormat,
    internal val isOpen: () -> Boolean,
) : FrameSource {
    public constructor() : this(sessionFormat = { FrameFormat.YUV_420 }, isOpen = { true })

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration = TODO("S2' red")
}
