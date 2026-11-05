@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A holder built to replace another (a configuration change in the retained Compose path) starts its
 * engine only once the old engine has closed, and gives up waiting after PREVIOUS_CLOSE_TIMEOUT with
 * a warning (w7-design-decisions.md G6).
 */
class StartAfterCloseTest {
    private val warnings = mutableListOf<String>()

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    /** The fake, logging its start and the end of its close into a log both engines share. */
    private class LoggedEngine(
        val name: String,
        val log: MutableList<String>,
        val fake: FakeCameraEngine = FakeCameraEngine(),
        val closing: suspend () -> Unit = {},
    ) : CameraEngine by fake {
        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
            log += "$name:start"
            return fake.start(desired)
        }

        override suspend fun closeAndJoin() {
            fake.close()
            closing()
            log += "$name:closed"
        }
    }

    private fun TestScope.holder(engine: CameraEngine, startAfter: Job? = null) = CameraKStateHolder(
        CameraConfiguration(),
        CameraEngineFactory { _, _ -> engine },
        emptyList(),
        cameraKTestEnvironment(),
        startAfter,
    )

    @Test
    fun newStartWaitsUntilTheOldEngineHasClosed() = runTest {
        val log = mutableListOf<String>()
        val closeGate = CompletableDeferred<Unit>()
        val old = holder(LoggedEngine("old", log, closing = { closeGate.await() }))
        old.start()
        runCurrent()
        assertIs<CameraKState.Ready>(old.cameraState.value)

        old.close()
        val newEngine = LoggedEngine("new", log)
        val replacement = holder(newEngine, startAfter = old.closeCompletion)
        replacement.start()
        runCurrent()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf("old:start"), log, "the new engine started while the old one was still closing")

        closeGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("old:start", "old:closed", "new:start"), log)
        assertIs<CameraKState.Ready>(replacement.cameraState.value)
    }

    @Test
    fun newStartProceedsAfterTenSecondsWhenTheOldCloseHangs() = runTest {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
        val log = mutableListOf<String>()
        val old = holder(LoggedEngine("old", log, closing = { awaitCancellation() }))
        old.start()
        runCurrent()

        old.close()
        val newEngine = LoggedEngine("new", log)
        val replacement = holder(newEngine, startAfter = old.closeCompletion)
        replacement.start()
        runCurrent()
        advanceTimeBy(PREVIOUS_CLOSE_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertEquals(0, newEngine.fake.startCalls, "the new start did not wait for the old close")
        assertEquals(CameraKState.Starting, replacement.cameraState.value)
        val warnedBefore = warnings.size
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(10.seconds, PREVIOUS_CLOSE_TIMEOUT)
        assertEquals(1, newEngine.fake.startCalls, "the new start still waits on a close that hangs")
        assertIs<CameraKState.Ready>(replacement.cameraState.value)
        assertTrue(
            warnings.drop(warnedBefore).any { "close" in it },
            "starting without the old close logged no warning: $warnings",
        )
    }
}
