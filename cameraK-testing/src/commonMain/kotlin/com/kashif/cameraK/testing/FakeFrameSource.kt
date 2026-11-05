package com.kashif.cameraK.testing

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.AnalyzerAdmission
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A frame source for tests. Its frame output is there from the start: a registration whose
 * [FrameSpec.acceptedFormats] holds the session format is active until it is closed. Any other is
 * rejected with `Unsupported(ANALYSIS_FORMAT)` and stays inactive.
 *
 * [emit] delivers a frame on the calling thread, through the same admission as the platform
 * sources: a registration whose analyzer is still running skips the frame, and one with a
 * [FrameSpec.maxFps] skips every frame stamped inside that interval since the last frame it
 * received. The clock is the emitted frames' [CameraFrame.timestampNanos], so a test sets the rate
 * by the timestamps it emits and keeps them non-decreasing. An admitted frame reaches the analyzer
 * as the object emitted. [FrameSpec.maxRetained] is not enforced: holding frames is the platform
 * sources' job, and a [TestFrame] holds nothing.
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
     * open; while it is not, the frame reaches nobody. Each registration admits it or skips it as
     * the class KDoc says. What an analyzer throws is caught and logged, and the others still
     * receive the frame, as the production frame sources do: a failed
     * assertion inside an analyzer is logged too, so a test asserts on what the analyzer recorded,
     * after [emit] returns. A fatal throwable, such as an OutOfMemoryError, propagates.
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
        return Registration(spec, analyzer, rejection).also { registered += it }
    }

    @OptIn(InternalCameraKApi::class)
    private inner class Registration(
        spec: FrameSpec,
        private val analyzer: FrameAnalyzer,
        override val rejection: CameraKError?,
    ) : FrameRegistration {
        private val _active = MutableStateFlow(rejection == null)
        override val active: StateFlow<Boolean> = _active.asStateFlow()

        // The admission's clock: the timestamp of the frame being admitted.
        private var frameTime = 0L
        private val admission = AnalyzerAdmission(spec.maxFps) { frameTime }

        fun deliver(frame: CameraFrame) {
            if (!_active.value) return
            frameTime = frame.timestampNanos
            admission.deliver { analyzer.analyze(frame) }
        }

        override fun close() {
            _active.value = false
            registered.remove(this)
        }
    }
}
