@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine is its own LifecycleOwner: start resumes it, stop moves it back to CREATED with its use
 * cases still bound, and close destroys it. A stop and a start reopen the camera without a rebind,
 * which is the engine half of a rotation that keeps the camera.
 */
@RunWith(RobolectricTestRunner::class)
class EngineStopStartTest {
    private val platform = FakeAndroidPlatform()
    private val success = CameraKResult.Success(Unit)

    @Test
    fun stopThenStartReopensWithoutARebind() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(success, engine.start(desired))
        platform.settle(testScheduler)

        engine.stop()
        platform.settle(testScheduler)
        assertEquals(EngineState.Closed, engine.state.value, "after stop")
        assertEquals(Lifecycle.State.CREATED, engine.lifecycle.currentState, "after stop")

        assertEquals(success, engine.start(desired), "start after stop")
        platform.settle(testScheduler)
        assertEquals(EngineState.Open, engine.state.value, "after the second start")
        assertEquals(Lifecycle.State.RESUMED, engine.lifecycle.currentState, "after the second start")
        assertEquals(1, platform.backend.binding.bindCalls, "stop and start rebound the use cases")
        engine.closeAndJoin()
    }

    // Replaces AndroidLegacySessionTest.registryFollowsStartStopAndClose: the engine is the registry now.
    @Test
    fun registryFollowsStartStopAndClose() = runTest {
        val engine = platform.engine(testScheduler)
        val offMain = mutableListOf<Lifecycle.Event>()
        engine.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (Looper.myLooper() != Looper.getMainLooper()) offMain += event },
        )
        assertEquals(Lifecycle.State.INITIALIZED, engine.lifecycle.currentState, "after construction")

        assertEquals(success, engine.start(desired))
        assertEquals(Lifecycle.State.RESUMED, engine.lifecycle.currentState, "after start")

        engine.stop()
        assertEquals(Lifecycle.State.CREATED, engine.lifecycle.currentState, "after stop")

        assertEquals(success, engine.start(desired))
        assertEquals(Lifecycle.State.RESUMED, engine.lifecycle.currentState, "after a second start")

        engine.closeAndJoin()
        assertEquals(Lifecycle.State.DESTROYED, engine.lifecycle.currentState, "after close")
        assertTrue(offMain.isEmpty(), "registry moves off the main looper: $offMain")
    }
}
