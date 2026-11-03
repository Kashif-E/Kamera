package com.kashif.cameraK.testing

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A frame source for tests. Its frame output is there from the start: a registration whose
 * [FrameSpec.acceptedFormats] holds the session format is active until it is closed. Any other is
 * rejected with `Unsupported(ANALYSIS_FORMAT)` and stays inactive.
 *
 * [emit] delivers a frame on the calling thread. It does not apply [FrameSpec.maxFps] or
 * [FrameSpec.maxRetained]: every frame emitted while the session is open reaches every active
 * registration, so a test sets the frame rate by what it emits.
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

    /**
     * Delivers [frame] to every active registration, in registration order, while the session is
     * open; while it is not, the frame reaches nobody. An analyzer that throws an exception is
     * logged and the others still receive the frame. An [Error], such as a failed assertion inside
     * an analyzer, is not caught, so it fails the test that emitted the frame.
     *
     * @throws IllegalArgumentException when [frame] is not in the session's format: a session
     *   delivers frames in its own format only.
     */
    public fun emit(frame: CameraFrame) {
        val session = sessionFormat()
        require(frame.format == session) { "a ${frame.format} frame emitted into a $session session" }
        if (!isOpen()) return
        for (registration in registered.toList()) registration.deliver(frame)
    }

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration {
        val rejection =
            if (sessionFormat() in
                spec.acceptedFormats
            ) {
                null
            } else {
                CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
            }
        return Registration(analyzer, rejection).also { registered += it }
    }

    private inner class Registration(private val analyzer: FrameAnalyzer, override val rejection: CameraKError?) :
        FrameRegistration {
        private val _active = MutableStateFlow(rejection == null)
        override val active: StateFlow<Boolean> = _active.asStateFlow()

        fun deliver(frame: CameraFrame) {
            if (!_active.value) return
            try {
                analyzer.analyze(frame)
            } catch (e: Exception) {
                CameraKLogger.w(TAG, "Frame analyzer threw; the other analyzers still receive the frame", e)
            }
        }

        override fun close() {
            _active.value = false
            registered.remove(this)
        }
    }

    private companion object {
        const val TAG = "CameraK"
    }
}
