@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CameraKTestEnvironmentTest {
    @Test
    fun delayAndTimeSourceShareTheTestScheduler() = runTest {
        val environment = cameraKTestEnvironment()
        val mark = environment.timeSource.markNow()

        withContext(environment.dispatchers.default) { delay(5.seconds) }
        withContext(environment.dispatchers.io) { delay(1.seconds) }
        withContext(environment.dispatchers.main) { delay(1.seconds) }

        assertEquals(7.seconds, mark.elapsedNow(), "the environment's clock")
        assertEquals(7_000, testScheduler.currentTime, "the test scheduler's clock")
    }

    @Test
    fun parentJobIsAChildOfBackgroundScopeAndStartsWithoutChildren() = runTest {
        val parent = checkNotNull(cameraKTestEnvironment().parentJob)

        assertTrue(
            parent in backgroundScope.coroutineContext.job.children.toList(),
            "parentJob is not under backgroundScope",
        )
        assertTrue(parent.children.none(), "a fresh parentJob already has children")
        assertTrue(parent !== cameraKTestEnvironment().parentJob, "each call gets its own parentJob")
    }

    @Test
    fun scopeLeftOpenUnderParentJobDoesNotHangRunTest() = runTest {
        val environment = cameraKTestEnvironment()
        val left = CoroutineScope(checkNotNull(environment.parentJob) + environment.dispatchers.default)

        // A holder the test forgot to close: runTest still completes, and cancels it.
        left.launch { awaitCancellation() }
    }

    @Test
    fun defaultPermissionCheckerIsTheFake() = runTest {
        val checker = checkNotNull(cameraKTestEnvironment().permissionChecker)

        assertTrue(checker is FakePermissionChecker)
    }
}
