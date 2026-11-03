@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bridge owns the 1.x controller's LifecycleOwner: start resumes it, stop moves it to CREATED
 * without unbinding, close destroys it, and every move is on the main looper.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidLegacySessionTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun registryFollowsStartStopAndClose() = runTest {
        // Robolectric runs the test on the main looper's thread, so the test scheduler's thread is the main thread.
        val main = StandardTestDispatcher(testScheduler)
        val environment = CameraKEnvironment(dispatchers = CameraKDispatchers(main = main, default = main, io = main))
        val engine = createPlatformEngine(
            CameraConfiguration(),
            CameraKPlatformContext(ApplicationProvider.getApplicationContext()),
            environment,
        )
        val lifecycle = assertIs<LegacyControllerAccess>(engine).legacyController.lifecycleOwner.lifecycle
        val offMain = mutableListOf<Lifecycle.Event>()
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (Looper.myLooper() != Looper.getMainLooper()) offMain += event },
        )

        assertEquals(Lifecycle.State.INITIALIZED, lifecycle.currentState, "after createPlatformEngine")

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState, "after start")

        engine.stop()
        assertEquals(Lifecycle.State.CREATED, lifecycle.currentState, "after stop")

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState, "after a second start")

        engine.closeAndJoin()
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.currentState, "after close")
        assertTrue(offMain.isEmpty(), "registry moves off the main looper: $offMain")
    }
}
