@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CameraKScreen(holder) shows one slot per state (design §10.3): Idle and Starting show `loading`,
 * Ready and Interrupted the preview with `content` over it, Error the `error` slot, Closed nothing.
 * The default error slot retries a retryable error on its own, once until the camera is Ready again,
 * and leaves Error(Permission) to the resume re-check.
 */
class CameraKScreenSlotTest {
    /**
     * Runs the composition's coroutines (its effects, state collectors and recomposer) only when a frame
     * is drawn, as a device's main thread does, rather than the moment they are resumed.
     */
    private class FrameDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun drain() {
            while (true) {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }

    private class Rig(
        private val scope: TestScope,
        val holder: CameraKStateHolder,
        defaultError: Boolean,
        private val frameDispatcher: FrameDispatcher? = null,
    ) {
        /** The slots in the composition now. */
        val showing = mutableSetOf<String>()

        /** How many times each slot entered the composition. */
        val entered = mutableMapOf<String, Int>()
        var errorSeen: CameraKError? = null
        var retrySeen: (() -> Unit)? = null

        private val loading: @Composable () -> Unit = { Track("loading") }
        private val content: @Composable CameraPreviewScope.() -> Unit = { Track("content") }
        private val error: @Composable (CameraKError, () -> Unit) -> Unit = { error, retry ->
            errorSeen = error
            retrySeen = retry
            Track("error")
        }

        private val screen: @Composable () -> Unit = {
            if (defaultError) {
                CameraKScreen(holder, Modifier.fillMaxSize(), loading = loading, content = content)
            } else {
                CameraKScreen(holder, Modifier.fillMaxSize(), loading = loading, error = error, content = content)
            }
        }

        private val scene = if (frameDispatcher != null) {
            ImageComposeScene(width = WIDTH, height = HEIGHT, coroutineContext = frameDispatcher, content = screen)
        } else {
            ImageComposeScene(width = WIDTH, height = HEIGHT, content = screen)
        }

        @Composable
        private fun Track(slot: String) {
            DisposableEffect(Unit) {
                showing += slot
                entered[slot] = (entered[slot] ?: 0) + 1
                onDispose { showing -= slot }
            }
        }

        val state: CameraKState get() = holder.cameraState.value

        fun frame(): PixelMap {
            frameDispatcher?.drain()
            Snapshot.sendApplyNotifications()
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            frameDispatcher?.drain()
            scope.runCurrent()
            Snapshot.sendApplyNotifications()
            return pixels
        }

        /** Renders until [drawn] holds: the desktop preview draws a frame its collector converted off the UI thread. */
        fun awaitPixels(what: String, drawn: (PixelMap) -> Boolean): PixelMap {
            val deadline = System.nanoTime() + AWAIT_NANOS
            while (true) {
                val pixels = frame()
                if (drawn(pixels)) return pixels
                if (System.nanoTime() > deadline) fail("$what: not drawn within ${AWAIT_NANOS / 1_000_000} ms")
                Thread.sleep(5)
            }
        }

        fun close() = scene.close()
    }

    private fun TestScope.rig(
        fake: FakeCameraEngine = FakeCameraEngine(),
        checker: FakePermissionChecker = FakePermissionChecker(),
        defaultError: Boolean = false,
        effectsOnFrames: Boolean = false,
    ): Pair<Rig, FakeCameraEngine> = Rig(
        this,
        fakeCameraKStateHolder(fake = fake, permissionChecker = checker),
        defaultError,
        if (effectsOnFrames) FrameDispatcher() else null,
    ) to fake

    // The fake's fixed frame is 640 x 480, left half 0x202020 and right half 0xE0E0E0; the scene is 4:3.
    private fun PixelMap.showsTheFixedFrame(): Boolean =
        rgb(WIDTH / 4, HEIGHT / 2) == DARK && rgb(WIDTH * 3 / 4, HEIGHT / 2) == LIGHT

    private fun PixelMap.isBlank(): Boolean =
        (0 until HEIGHT).all { y -> (0 until WIDTH).all { x -> this[x, y].toArgb() ushr 24 == 0 } }

    private fun PixelMap.rgb(x: Int, y: Int): Int = this[x, y].toArgb() and 0xFFFFFF

    @Test
    fun idleAndStartingShowLoading() = runTest {
        val (rig, fake) = rig()
        try {
            rig.frame()
            assertEquals(CameraKState.Idle, rig.state)
            assertEquals(setOf("loading"), rig.showing, "Idle")

            fake.autoOpen = false
            rig.holder.start()
            runCurrent()
            val pixels = rig.frame()
            assertEquals(CameraKState.Starting, rig.state)
            assertEquals(setOf("loading"), rig.showing, "Starting")
            assertTrue(pixels.isBlank(), "no preview while Starting")
        } finally {
            rig.close()
        }
    }

    @Test
    fun readyAndInterruptedShowPreviewAndContent() = runTest {
        val (rig, fake) = rig()
        try {
            rig.holder.start()
            runCurrent()
            assertIs<CameraKState.Ready>(rig.state)
            rig.awaitPixels("the preview in Ready") { it.showsTheFixedFrame() }
            assertEquals(setOf("content"), rig.showing, "Ready")

            fake.interrupt(InterruptionReason.MULTITASKING)
            runCurrent()
            assertEquals(CameraKState.Interrupted(InterruptionReason.MULTITASKING), rig.state)
            rig.awaitPixels("the preview in Interrupted") { it.showsTheFixedFrame() }
            assertEquals(setOf("content"), rig.showing, "Interrupted")
            assertEquals(1, rig.entered["content"], "content left and re-entered between Ready and Interrupted")
        } finally {
            rig.close()
        }
    }

    @Test
    fun errorShowsErrorSlot() = runTest {
        val (rig, fake) = rig()
        try {
            fake.autoOpen = false
            rig.holder.start()
            runCurrent()
            fake.failOpen(CameraKError.CameraDisabled())
            runCurrent()
            val state = assertIs<CameraKState.Error>(rig.state)

            val pixels = rig.frame()
            assertEquals(setOf("error"), rig.showing)
            assertSame(state.error, rig.errorSeen, "the slot gets the state's error")
            assertTrue(pixels.isBlank(), "no preview in Error")
        } finally {
            rig.close()
        }
    }

    @Test
    fun closedShowsNothing() = runTest {
        val (rig, _) = rig()
        try {
            rig.holder.start()
            runCurrent()
            rig.awaitPixels("the preview in Ready") { it.showsTheFixedFrame() }

            rig.holder.close()
            runCurrent()
            assertEquals(CameraKState.Closed, rig.state)
            val pixels = rig.frame()
            assertEquals(emptySet(), rig.showing)
            assertTrue(pixels.isBlank(), "the preview is still drawn after close")
        } finally {
            rig.close()
        }
    }

    @Test
    fun defaultErrorSlotRetriesRetryableError() = runTest {
        val (rig, fake) = rig(defaultError = true)
        try {
            fake.autoOpen = false
            rig.holder.start()
            runCurrent()
            fake.failOpen(CameraKError.CameraInUse())
            runCurrent()
            assertIs<CameraKState.Error>(rig.state)

            rig.frame()
            assertEquals(2, fake.startCalls, "the default slot did not retry: ${fake.calls}")
            assertEquals(CameraKState.Starting, rig.state)

            fake.deliverOpen()
            runCurrent()
            assertIs<CameraKState.Ready>(rig.state)
        } finally {
            rig.close()
        }
    }

    @Test
    fun defaultErrorSlotDoesNotRetryPermission() = runTest {
        val checker = FakePermissionChecker(PermissionStatus.Denied(shouldShowRationale = true))
        val (rig, fake) = rig(checker = checker, defaultError = true)
        try {
            rig.holder.start()
            runCurrent()
            assertIs<CameraKError.Permission>(assertIs<CameraKState.Error>(rig.state).error)
            rig.frame()

            // A retry now would pass the gate and open the camera; only the resume re-check may.
            checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
            rig.frame()
            rig.frame()

            assertIs<CameraKState.Error>(rig.state, "state is ${rig.state}")
            assertEquals(0, fake.startCalls, "the default slot retried Error(Permission): ${fake.calls}")
        } finally {
            rig.close()
        }
    }

    /** A retry that fails again is not retried in a loop; reaching Ready re-arms the one retry. */
    @Test
    fun defaultErrorSlotRetriesOnceUntilTheCameraIsReadyAgain() = runTest {
        val (rig, fake) = rig(defaultError = true)
        try {
            fake.autoOpen = false
            rig.holder.start()
            runCurrent()
            fake.failOpen(CameraKError.CameraInUse())
            runCurrent()
            rig.frame()
            assertEquals(2, fake.startCalls, "first failure: ${fake.calls}")

            fake.failOpen(CameraKError.CameraInUse())
            runCurrent()
            rig.frame()
            rig.frame()
            assertIs<CameraKState.Error>(rig.state)
            assertEquals(2, fake.startCalls, "the default slot retried a second time in a row: ${fake.calls}")

            rig.holder.retry()
            runCurrent()
            fake.deliverOpen()
            runCurrent()
            rig.frame()
            assertIs<CameraKState.Ready>(rig.state)

            fake.fail(CameraKError.Disconnected())
            runCurrent()
            rig.frame()
            assertEquals(4, fake.startCalls, "no retry after a failure that followed Ready: ${fake.calls}")
        } finally {
            rig.close()
        }
    }

    /**
     * Reaching Ready re-arms the one retry even when the camera fails again before the composition
     * draws a frame: a state collector in the composition sees only the last of the two.
     */
    @Test
    fun defaultErrorSlotRetriesAfterAReadyTheCompositionNeverDrew() = runTest {
        val (rig, fake) = rig(defaultError = true, effectsOnFrames = true)
        try {
            fake.autoOpen = false
            rig.holder.start()
            runCurrent()
            fake.failOpen(CameraKError.CameraInUse())
            runCurrent()
            rig.frame()
            assertEquals(2, fake.startCalls, "first failure: ${fake.calls}")

            // The holder reaches Ready and fails again, with no frame drawn in between.
            fake.deliverOpen()
            runCurrent()
            assertIs<CameraKState.Ready>(rig.state)
            fake.fail(CameraKError.Disconnected())
            runCurrent()
            rig.frame()
            rig.frame()
            assertEquals(3, fake.startCalls, "no retry after a failure that followed Ready: ${fake.calls}")
        } finally {
            rig.close()
        }
    }

    /** The retry an app's own error slot gets restarts the holder every time it is called. */
    @Test
    fun customErrorSlotRetryRestartsEveryTime() = runTest {
        val (rig, fake) = rig()
        try {
            fake.autoOpen = false
            rig.holder.start()
            runCurrent()
            repeat(2) { attempt ->
                fake.failOpen(CameraKError.CameraInUse())
                runCurrent()
                rig.frame()
                assertEquals(attempt + 1, fake.startCalls, "the screen retried on its own: ${fake.calls}")
                val retry = rig.retrySeen ?: fail("the error slot got no retry")
                retry()
                runCurrent()
                assertEquals(attempt + 2, fake.startCalls, "retry ${attempt + 1} did not restart: ${fake.calls}")
            }
        } finally {
            rig.close()
        }
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        const val DARK = 0x202020
        const val LIGHT = 0xE0E0E0
        const val AWAIT_NANOS = 5_000_000_000L
    }
}
