package com.kashif.cameraK.engine

import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec

/** The 1.x bridge's frame source: its registrations never become active. S2' red: the declaration only. */
internal class InactiveFrameSource : FrameSource {
    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration = TODO("S2' red")
}
