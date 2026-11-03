@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The holder's lifecycle over [FakeCameraEngine]: the engine reports its own state, and the holder
 * maps it, owns its scope, gates on permission and closes in order.
 */
class CameraKStateHolderLifecycleTest {
    private class Logged(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?)

    private val logged = mutableListOf<Logged>()

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    private fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable -> logged += Logged(level, message, throwable) }
    }

    /** A holder over [engine], and every state it published, collected without conflation. */
    private class Session(
        val holder: CameraKStateHolder,
        val environment: CameraKEnvironment,
        val states: List<CameraKState>,
    ) {
        val state: CameraKState get() = holder.cameraState.value
    }

    private fun TestScope.session(
        engine: CameraEngine,
        checker: CameraKPermissionChecker = FakePermissionChecker(),
        plugins: List<CameraKPlugin> = emptyList(),
        environment: CameraKEnvironment = cameraKTestEnvironment(checker),
    ): Session {
        val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> engine }, plugins, environment)
        val states = mutableListOf<CameraKState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.cameraState.collect { states += it } }
        return Session(holder, environment, states)
    }

    /** The fake with knobs a platform can turn: a stop that waits, a stop that does nothing, a close that never returns. */
    private class ScriptedEngine(val fake: FakeCameraEngine) : CameraEngine by fake {
        var stopGate: CompletableDeferred<Unit>? = null
        var stopLeavesStateAlone = false
        var closeHangs = false

        override suspend fun stop() {
            stopGate?.await()
            if (!stopLeavesStateAlone) fake.stop()
        }

        override suspend fun closeAndJoin() {
            if (!closeHangs) return fake.closeAndJoin()
            fake.close()
            awaitCancellation()
        }
    }

    private class CountingPlugin(private val onAttached: (CameraKStateHolder) -> Unit = {}) : CameraKPlugin {
        var attaches = 0
        var detaches = 0

        override fun onAttach(stateHolder: CameraKStateHolder) {
            attaches++
            onAttached(stateHolder)
        }

        override fun onDetach() {
            detaches++
        }
    }

    private fun Session.ready(): CameraKState.Ready = assertIs(state, "state is $state")

    private fun Session.error(): CameraKError = assertIs<CameraKState.Error>(state, "state is $state").error

    @Test
    fun readyOnlyAfterEngineOpen() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)

        s.holder.start()
        runCurrent()

        assertEquals(CameraKState.Starting, s.state)
        assertEquals(listOf("start"), fake.calls)
        assertTrue(s.states.none { it is CameraKState.Ready }, "Ready before the platform opened: ${s.states}")

        fake.deliverOpen()
        runCurrent()

        s.ready()
    }

    @Test
    fun failedOpenGivesMappedErrorAndRetryReachesReady() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()
        val failure = CameraKError.CameraInUse()

        fake.failOpen(failure)
        runCurrent()

        assertSame(failure, s.error())
        assertEquals("stop", fake.calls.last(), "the failed start left the engine unstopped: ${fake.calls}")

        fake.autoOpen = true
        assertTrue(s.holder.retry(), "retry from Error")
        runCurrent()

        s.ready()
    }

    @Test
    fun interruptedDuringStartGivesInterruptedThenReady() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()

        fake.interrupt(InterruptionReason.IN_USE_BY_ANOTHER_CLIENT)
        runCurrent()

        val interrupted = assertIs<CameraKState.Interrupted>(s.state, "state is ${s.state}")
        assertEquals(InterruptionReason.IN_USE_BY_ANOTHER_CLIENT, interrupted.reason)

        fake.endInterruption()
        runCurrent()

        s.ready()
    }

    @Test
    fun startTimeoutGivesTimeoutStartAndStopsEngine() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()

        advanceTimeBy(START_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertEquals(CameraKState.Starting, s.state, "gave up before START_TIMEOUT")

        advanceTimeBy(2.milliseconds)
        runCurrent()

        val timeout = assertIs<CameraKError.Timeout>(s.error())
        assertEquals(CameraOperation.START, timeout.operation)
        assertEquals("stop", fake.calls.last(), "the timed-out start left the engine unstopped: ${fake.calls}")
        assertEquals(EngineState.Closed, fake.state.value)
    }

    @Test
    fun stopDuringStartingGivesIdleWithoutErrorAndStopsEngine() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()

        s.holder.stop()
        runCurrent()
        fake.deliverOpen()
        runCurrent()

        assertEquals(CameraKState.Idle, s.state)
        assertTrue(s.states.none { it is CameraKState.Error }, "a stop published an error: ${s.states}")
        assertTrue(fake.stopCalls >= 1, "the engine was never stopped: ${fake.calls}")
        assertEquals(EngineState.Closed, fake.state.value, "a late open reopened the camera")
    }

    @Test
    fun closeDuringStartingGivesClosedOneCloseAndNoReady() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()

        s.holder.close()
        runCurrent()
        fake.deliverOpen()
        runCurrent()

        assertEquals(CameraKState.Closed, s.state)
        assertEquals(1, fake.closeCalls, "engine closes: ${fake.calls}")
        assertTrue(s.states.none { it is CameraKState.Ready }, "Ready during close: ${s.states}")
    }

    @Test
    fun permanentlyDeniedPermissionGivesPermissionErrorAndEngineNeverStarts() = runTest {
        val fake = FakeCameraEngine()
        val s = session(fake, FakePermissionChecker(PermissionStatus.PermanentlyDenied))

        s.holder.start()
        runCurrent()

        val permission = assertIs<CameraKError.Permission>(s.error())
        assertEquals(CameraKPermission.CAMERA, permission.permission)
        assertEquals(PermissionStatus.PermanentlyDenied, permission.status)
        assertEquals(0, fake.startCalls, "the engine started without permission")
    }

    @Test
    fun throwingOnAttachLeavesCameraReady() = runTest {
        val throwing = CountingPlugin { throw IllegalStateException("plugin attach failed") }
        val healthy = CountingPlugin()
        val s = session(FakeCameraEngine(), plugins = listOf(throwing, healthy))

        s.holder.start()
        runCurrent()

        s.ready()
        assertEquals(1, throwing.attaches)
        assertEquals(1, healthy.attaches, "a plugin after the throwing one was not attached")
    }

    @Test
    fun closeAndJoinLeavesNoChildCoroutines() = runTest {
        @Suppress("DEPRECATION")
        val plugin = CountingPlugin { it.pluginScope.launch { awaitCancellation() } }
        val s = session(FakeCameraEngine(), plugins = listOf(plugin))
        s.holder.start()
        runCurrent()
        s.ready()

        s.holder.closeAndJoin()

        val children = s.environment.parentJob!!.children.toList()
        assertTrue(children.none(), "coroutines left after closeAndJoin: $children")
    }

    @Test
    fun closeDetachesPluginsThenClosesEngine() = runTest {
        val fake = FakeCameraEngine()
        val callsAtDetach = mutableListOf<List<String>>()
        val plugins = List(2) {
            object : CameraKPlugin {
                override fun onAttach(stateHolder: CameraKStateHolder) = Unit

                override fun onDetach() {
                    callsAtDetach += fake.calls
                }
            }
        }
        val s = session(fake, plugins = plugins)
        s.holder.start()
        runCurrent()

        s.holder.closeAndJoin()

        assertEquals(2, callsAtDetach.size, "plugins detached")
        callsAtDetach.forEach { assertFalse("close" in it, "a plugin was detached after the engine closed: $it") }
        assertEquals(1, fake.closeCalls)
    }

    @Test
    fun hangingEngineCloseIsBoundedAndLogged() = runTest {
        captureLogs()
        val engine = ScriptedEngine(FakeCameraEngine()).apply { closeHangs = true }
        val s = session(engine)
        s.holder.start()
        runCurrent()

        val closing = launch { s.holder.closeAndJoin() }
        runCurrent()
        assertFalse(closing.isCompleted, "closeAndJoin returned while the engine was still closing")

        advanceTimeBy(ENGINE_CLOSE_TIMEOUT + 1.milliseconds)
        runCurrent()

        assertTrue(closing.isCompleted, "closeAndJoin still waits on an engine that never closes")
        assertEquals(CameraKState.Closed, s.state)
        assertTrue(
            logged.any { it.level == CameraKLogger.Level.ERROR && it.throwable != null },
            "the bounded engine close was not logged with a throwable: ${logged.map { it.message }}",
        )
    }

    // Runtime failure: the session ends at Error and the engine is stopped.

    @Test
    fun runtimeFailureGivesErrorAndStopsTheEngine() = runTest {
        val fake = FakeCameraEngine()
        val s = session(fake)
        s.holder.start()
        runCurrent()
        val failure = CameraKError.Disconnected()

        fake.fail(failure)
        runCurrent()

        assertSame(failure, s.error())
        assertEquals("stop", fake.calls.last(), "calls: ${fake.calls}")
    }

    @Test
    fun platformReopenAfterRuntimeFailureLeavesError() = runTest {
        val engine = ScriptedEngine(FakeCameraEngine())
        val s = session(engine)
        s.holder.start()
        runCurrent()
        engine.stopGate = CompletableDeferred()

        engine.fake.fail(CameraKError.Disconnected())
        runCurrent()
        // The platform reopens on its own before the holder's stop reaches it.
        engine.fake.reopenAfterFailure()
        runCurrent()

        assertIs<CameraKState.Error>(s.state, "a platform reopen after a runtime failure moved the holder: ${s.states}")
        engine.stopGate!!.complete(Unit)
        runCurrent()
        assertIs<CameraKState.Error>(s.state, "state after the stop: ${s.states}")
    }

    @Test
    fun retryAfterRuntimeFailureStartsANewSessionFromAStoppedEngine() = runTest {
        val fake = FakeCameraEngine()
        val s = session(fake)
        s.holder.start()
        runCurrent()
        fake.fail(CameraKError.Disconnected())
        runCurrent()

        assertTrue(s.holder.retry(), "retry from Error")
        runCurrent()

        s.ready()
        assertEquals(listOf("start", "stop", "start"), fake.calls)
    }

    @Test
    fun startFromErrorRestartsLikeRetry() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()
        fake.failOpen(CameraKError.CameraInUse())
        runCurrent()
        s.error()

        fake.autoOpen = true
        s.holder.start()
        runCurrent()

        s.ready()
    }

    // The legacy facade channel.

    @Test
    fun readyOverAFakeEngineThrowsOnControllerAccess() = runTest {
        val s = session(FakeCameraEngine())
        s.holder.start()
        runCurrent()
        val ready = s.ready()

        assertFailsWith<IllegalStateException> {
            @Suppress("DEPRECATION")
            ready.controller
        }
    }

    // Decision 14's call site and the permission checker the engine constructor resolves.

    @Test
    fun effectiveConfigIsPassedToTheEngineFactory() = runTest {
        val config = CameraConfiguration(cameraLens = CameraLens.FRONT, imageFormat = ImageFormat.PNG)
        var received: CameraConfiguration? = null

        CameraKStateHolder(
            config,
            { c, _ ->
                received = c
                FakeCameraEngine()
            },
            listOf(CountingPlugin()),
            cameraKTestEnvironment(),
        )

        assertEquals(config, received, "the configuration the engine factory got")
    }

    @Test
    fun nullPermissionCheckerOpensWithoutACheck() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val environment = CameraKEnvironment(
            dispatchers = CameraKDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
            timeSource = testScheduler.timeSource,
            parentJob = SupervisorJob(backgroundScope.coroutineContext.job),
            permissionChecker = null,
        )
        val s = session(FakeCameraEngine(), environment = environment)

        s.holder.start()
        runCurrent()

        s.ready()
    }

    // The stop path before RecordingController.

    @Test
    fun stopWhileReadyGivesIdleAfterOneEngineStop() = runTest {
        val fake = FakeCameraEngine()
        val s = session(fake)
        s.holder.start()
        runCurrent()

        s.holder.stop()
        runCurrent()

        assertEquals(CameraKState.Idle, s.state)
        assertEquals(listOf("start", "stop"), fake.calls)
    }

    @Test
    fun startRightAfterStopWaitsForTheEngineStop() = runTest {
        val engine = ScriptedEngine(FakeCameraEngine())
        val s = session(engine)
        s.holder.start()
        runCurrent()
        engine.stopGate = CompletableDeferred()

        s.holder.stop()
        runCurrent()
        s.holder.start()
        runCurrent()
        assertEquals(listOf("start"), engine.fake.calls, "the next start reached the engine during its stop")

        engine.stopGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf("start", "stop", "start"), engine.fake.calls)
        s.ready()
    }

    // What start returns, and what the engine replays to a new session.

    @Test
    fun startReturningFailureWithoutPublishingFailedGivesError() = runTest {
        val fake = FakeCameraEngine()
        val failure = CameraKError.Disconnected()
        fake.startReturnsWithoutPublishing(failure)
        val s = session(fake)

        s.holder.start()
        runCurrent()

        assertSame(failure, s.error())
    }

    @Test
    fun failOpenWithUnsupportedGivesErrorUnsupportedNotStarting() = runTest {
        val fake = FakeCameraEngine()
        fake.failOpen(CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))
        val s = session(fake)

        s.holder.start()
        runCurrent()

        assertEquals(CameraFeature.IMAGE_FORMAT, assertIs<CameraKError.Unsupported>(s.error()).feature)
    }

    @Test
    fun initialEngineClosedDoesNotOverwriteStarting() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)

        s.holder.start()
        runCurrent()

        assertEquals(CameraKState.Starting, s.state)
        val sinceStarting = s.states.drop(s.states.indexOf(CameraKState.Starting))
        assertTrue(
            sinceStarting.none {
                it == CameraKState.Idle
            },
            "the engine's Closed overwrote Starting: ${s.states}",
        )

        fake.deliverOpen()
        runCurrent()
        s.ready()
    }

    @Test
    fun retryAfterFailureDoesNotReplayTheStaleFailed() = runTest {
        val engine = ScriptedEngine(FakeCameraEngine().apply { autoOpen = false }).apply { stopLeavesStateAlone = true }
        val s = session(engine)
        s.holder.start()
        runCurrent()
        engine.fake.failOpen(CameraKError.CameraInUse())
        runCurrent()
        s.error()
        assertIs<EngineState.Failed>(engine.fake.state.value, "setup: the engine still reports the failure")

        assertTrue(s.holder.retry())
        runCurrent()

        assertEquals(CameraKState.Starting, s.state, "the new session mapped the old session's Failed")
        engine.fake.deliverOpen()
        runCurrent()
        s.ready()
    }

    @Test
    fun startOnAnAlreadyOpenEngineReachesReady() = runTest {
        val fake = FakeCameraEngine()
        fake.start(
            DesiredHardware(
                selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
                flashMode = FlashMode.OFF,
                torchMode = TorchMode.OFF,
                zoomRatio = 1f,
                targetOrientation = null,
            ),
        )
        assertEquals(EngineState.Open, fake.state.value, "setup: the engine is open before the holder starts")
        val s = session(fake)

        s.holder.start()
        runCurrent()

        s.ready()
    }

    // Nothing leaves Closed, including what the engine reports while close is in its first step.

    @Test
    fun lateOpenDuringCloseRecordingStepKeepsClosed() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val s = session(fake)
        s.holder.start()
        runCurrent()

        s.holder.close()
        fake.deliverOpen()
        runCurrent()

        assertEquals(CameraKState.Closed, s.state)
        assertTrue(s.states.none { it is CameraKState.Ready }, "a late open reached Ready: ${s.states}")
    }

    @Test
    fun interruptionEndedDuringCloseKeepsClosed() = runTest {
        val fake = FakeCameraEngine()
        val s = session(fake)
        s.holder.start()
        runCurrent()
        fake.interrupt(InterruptionReason.OTHER)
        runCurrent()
        assertIs<CameraKState.Interrupted>(s.state)

        s.holder.close()
        fake.endInterruption()
        runCurrent()

        assertEquals(CameraKState.Closed, s.state)
        val sinceClosed = s.states.drop(s.states.indexOf(CameraKState.Closed))
        assertTrue(sinceClosed.all { it == CameraKState.Closed }, "the holder left Closed: ${s.states}")
    }
}
