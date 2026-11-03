package com.kashif.cameraK.testing

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A frame source for tests. For now a stub: it holds its registrations, whose [FrameRegistration.active]
 * stays false and whose [FrameRegistration.rejection] is null, and it delivers nothing.
 *
 * [FakeCameraEngine.fakeFrames] reads the session format from the engine's configuration and the
 * open state from the engine; the no-argument constructor, for a source used on its own, has a
 * YUV_420 session that is always open.
 */
public class FakeFrameSource internal constructor(
    internal val sessionFormat: () -> FrameFormat,
    internal val isOpen: () -> Boolean,
) : FrameSource {
    public constructor() : this(sessionFormat = { FrameFormat.YUV_420 }, isOpen = { true })

    private val registered = mutableListOf<Registration>()

    /** The registrations not yet closed, in registration order. */
    internal val registrations: List<FrameRegistration> get() = registered.toList()

    /** Delivers [frame] to the registered analyzers. */
    public fun emit(frame: CameraFrame): Unit = TODO("F0'")

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration =
        Registration().also { registered += it }

    private inner class Registration : FrameRegistration {
        override val active: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val rejection: CameraKError? = null

        override fun close() {
            registered.remove(this)
        }
    }
}
