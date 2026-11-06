package com.kashif.cameraK.plugin

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraUIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `launchPerSession` runs its block once per Ready session and cancels it when the camera leaves Ready. */
class LaunchPerSessionTest {
    private class SessionPlugin : ContextPlugin() {
        var started = 0
        var cancelled = 0

        override fun onAttach(context: CameraKPluginContext) {
            super.onAttach(context)
            context.launchPerSession {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
        }
    }

    @Test
    fun blockCancelledWhenCameraLeavesReady() = runTest {
        val plugin = SessionPlugin()
        val s = pluginSession(plugin)
        startReady(s)
        assertEquals(1, plugin.started, "the block did not start in the Ready session")

        s.engine.interrupt(InterruptionReason.OTHER)
        runCurrent()

        assertEquals(1, plugin.cancelled, "the block outlived the Ready session")
    }

    @Test
    fun blockRestartsOnNextReady() = runTest {
        val plugin = SessionPlugin()
        val s = pluginSession(plugin)
        startReady(s)

        s.holder.stop()
        runCurrent()
        startReady(s)

        assertEquals(2, plugin.started, "the block did not start again at the next Ready")
        assertEquals(1, plugin.cancelled, "the first session's block was not cancelled")
    }

    /**
     * Characterization: green on the base. Ready emitted again while the camera stays Ready is the
     * same session, so the block is not cancelled and launched again. `Ready` is one value in 2.0, so
     * the source re-emits it as a state flow never would. Removing `distinctUntilChanged` from
     * launchPerSession fails it.
     */
    @Test
    fun aRepeatedReadyStateDoesNotRelaunchTheBlock() = runTest {
        val state = ReEmittingState(CameraKState.Ready)
        val context = StateOnlyContext(state, this)
        var started = 0
        var cancelled = 0
        context.launchPerSession {
            started++
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        runCurrent()

        state.emit(CameraKState.Ready)
        runCurrent()

        assertEquals(1, started, "a second Ready value relaunched the block")
        assertEquals(0, cancelled, "a second Ready value cancelled the session's block")
        context.scope.cancel()
    }

    /** A state flow that hands its collectors every emitted value, an equal one included. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ReEmittingState(initial: CameraKState) : StateFlow<CameraKState> {
        private val emissions = MutableSharedFlow<CameraKState>(replay = 1).apply { tryEmit(initial) }

        override val value: CameraKState get() = emissions.replayCache.last()
        override val replayCache: List<CameraKState> get() = emissions.replayCache

        override suspend fun collect(collector: FlowCollector<CameraKState>): Nothing = emissions.collect(collector)

        fun emit(state: CameraKState) {
            check(emissions.tryEmit(state)) { "a replay-1 shared flow refused an emission" }
        }
    }

    /** A context whose only live member is [cameraState], for launchPerSession alone. */
    private class StateOnlyContext(override val cameraState: StateFlow<CameraKState>, test: TestScope) :
        CameraKPluginContext {
        override val scope: CoroutineScope = CoroutineScope(test.coroutineContext + Job())
        override val failure: StateFlow<CameraKError?> = MutableStateFlow(null)
        override val dispatchers: CameraKDispatchers get() = unused()
        override val uiState: StateFlow<CameraUIState> get() = unused()
        override val capabilities: StateFlow<CameraCapabilities> get() = unused()
        override val deviceOrientation: StateFlow<DeviceOrientation> get() = unused()
        override val recordingState: StateFlow<RecordingState> get() = unused()
        override val frames: FrameSource get() = unused()
        override val captures: SharedFlow<CapturedImage> get() = unused()

        override fun reportFailure(error: CameraKError) = unused()

        private fun unused(): Nothing = error("launchPerSession reads only cameraState and scope")
    }
}
