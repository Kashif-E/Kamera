@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Closing the engine while CameraX work is still queued on the main thread must leave the camera
 * closed: no queued bind may reopen it, and no late capture callback may throw.
 */
@RunWith(RobolectricTestRunner::class)
class CameraControllerReleaseTest {
    private val platform = FakeAndroidPlatform()
    private val backend = platform.backend

    @Test
    fun closeBeforeTheProviderResolves_leavesTheCameraClosed() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)

        // The start asks for the provider, then the engine closes before ProcessCameraProvider resolves.
        val start = async(start = CoroutineStart.UNDISPATCHED) { engine.start(FakeAndroidPlatform.desired) }
        engine.close()
        platform.settle(testScheduler)
        start.await()

        assertEquals(emptySet(), backend.binding.bound, "a bind queued before close reopened the camera")
        assertEquals(0, backend.binding.bindCalls, "bindToLifecycle ran after close")
    }

    @Test
    fun closeWithTwoFrameRegistrations_doesNotRebindWhileClosingThem() = runTest {
        // Two analyzer plugins' registrations, on an engine whose first bind includes the analysis.
        val engine = platform.engine(testScheduler, CameraConfiguration { frameAnalysis = true })
        open(platform, engine)
        repeat(2) { engine.frames.register(FrameSpec()) { } }
        platform.settle(testScheduler)
        assertEquals(1, backend.binding.bindCalls, "setup: the camera binds once")

        engine.close()
        platform.settle(testScheduler)

        assertEquals(emptySet(), backend.binding.bound, "closing a registration during close reopened the camera")
        assertEquals(1, backend.binding.bindCalls, "closing a registration during close queued a rebind")
    }

    @Test
    fun startAfterClose_isClosedAndBindsNothing() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        engine.close()
        // Every late path (recording Finalize, orientation flip, lens switch) goes through a start or a rebind.
        val again = engine.start(FakeAndroidPlatform.desired)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Failure(CameraKError.Closed), again, "a start after close")
        assertEquals(emptySet(), backend.binding.bound, "a bind after close reopened the camera")
        assertEquals(1, backend.binding.bindCalls, "bindToLifecycle ran after close")
    }

    @Test
    fun captureSavedAfterClose_resolvesTheCallerAndDoesNotThrowOnMain() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val capture = async(start = CoroutineStart.UNDISPATCHED) { engine.capture() }
        platform.settle(testScheduler)
        val pending = backend.captures.single()

        engine.close()
        // CameraX finishes writing the file and reports it after the engine was released.
        pending.deliverSaved(byteArrayOf(1, 2, 3))
        platform.settle(testScheduler)

        assertTrue(capture.isCompleted, "capture is still suspended after close")
        val result = capture.await()
        assertIs<CameraKResult.Failure>(result, "a capture interrupted by close must report a failure: $result")
    }
}
