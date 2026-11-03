@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import androidx.camera.core.CameraState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FRONT
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CameraX binding, the engine's LifecycleRegistry and every LiveData observer run on the engine's
 * main dispatcher, and the camera-state observer moves to each camera a bind returns.
 */
@RunWith(RobolectricTestRunner::class)
class MainThreadContractTest {
    private val platform = FakeAndroidPlatform()

    // Not the main thread: the engine's default and io dispatchers, where nothing of CameraX's may run.
    private val background: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "camerak-test-background") }.asCoroutineDispatcher()

    @After
    fun closeBackground() = background.close()

    @Test
    fun bindRegistryAndObserversRunOnMainDispatcher() = runTest {
        val main = Looper.getMainLooper().thread
        val engine = platform.engine(testScheduler, background = background)
        val offMain = mutableListOf<Lifecycle.Event>()
        engine.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (Thread.currentThread() != main) offMain += event },
        )

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))

        assertEquals(listOf(main), platform.backend.binding.bindThreads, "bindToLifecycle ran off the main thread")
        assertTrue(offMain.isEmpty(), "registry moves off the main thread: $offMain")
        assertTrue(platform.camera!!.cameraState.hasObservers(), "nothing observes the bound camera's state")
        assertFalse(main in platform.enumerations, "the cameras were enumerated on the main thread")

        engine.closeAndJoin()
        assertFalse(platform.camera!!.cameraState.hasObservers(), "close left the camera-state observer attached")
    }

    @Test
    fun observersReattachedAfterRebind() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val first = platform.camera!!

        val switched = engine.selectCamera(CameraSelection.ById(FRONT.id))
        assertEquals(FRONT.id, assertIs<CameraKResult.Success<LensInfo>>(switched).value.id)
        platform.settle(testScheduler)
        val second = platform.camera!!

        assertFalse(first.cameraState.hasObservers(), "the observer stayed on the camera the rebind replaced")
        assertTrue(second.cameraState.hasObservers(), "the observer did not move to the new camera")

        first.report(CameraState.create(CameraState.Type.OPENING, CameraState.StateError.create(IN_USE)))
        platform.settle(testScheduler)
        assertEquals(EngineState.Open, engine.state.value, "a report from the replaced camera changed the state")

        second.report(CameraState.create(CameraState.Type.OPENING, CameraState.StateError.create(IN_USE)))
        platform.settle(testScheduler)
        assertIs<EngineState.Interrupted>(engine.state.value, "the new camera's report")
        engine.closeAndJoin()
    }

    private companion object {
        const val IN_USE = CameraState.ERROR_CAMERA_IN_USE
    }
}
