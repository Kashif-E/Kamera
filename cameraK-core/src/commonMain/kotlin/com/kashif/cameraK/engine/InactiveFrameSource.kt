package com.kashif.cameraK.engine

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The 1.x bridge's frame source: its registrations never become active and are never rejected,
 * because the 1.x controllers deliver frames only to analyzers registered on `Ready.controller`.
 */
internal class InactiveFrameSource : FrameSource {
    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration = InactiveRegistration

    private object InactiveRegistration : FrameRegistration {
        override val active: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val rejection: CameraKError? = null

        override fun close() = Unit
    }
}
