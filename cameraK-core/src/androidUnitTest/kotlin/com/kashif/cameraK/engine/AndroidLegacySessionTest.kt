@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.util.concurrent.Executors
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

    // Not the main thread: a session call that ran on default or io would move the registry off the main looper.
    private val background: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "camerak-test-background") }.asCoroutineDispatcher()

    @After
    fun closeBackground() = background.close()

    // Robolectric runs the test on the main looper's thread, so the test scheduler's thread is the main thread.
    private fun TestScope.engine(): CameraEngine {
        val main = StandardTestDispatcher(testScheduler)
        val environment =
            CameraKEnvironment(dispatchers = CameraKDispatchers(main = main, default = background, io = background))
        return createPlatformEngine(
            CameraConfiguration(),
            CameraKPlatformContext(ApplicationProvider.getApplicationContext()),
            environment,
        )
    }

    @Test
    fun registryFollowsStartStopAndClose() = runTest {
        val engine = engine()
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

    @Test
    fun lateOrientationCallbackAfterCloseDoesNothing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensors = shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        sensors.addSensor(accelerometer)
        val engine = engine()
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))

        sensors.tilt(accelerometer, x = -GRAVITY, y = 0f)
        assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.deviceOrientation.value, "while open")

        engine.close()
        sensors.tilt(accelerometer, x = 0f, y = GRAVITY)
        assertEquals(
            DeviceOrientation.LANDSCAPE_RIGHT,
            engine.deviceOrientation.value,
            "an orientation callback after close changed deviceOrientation",
        )

        engine.closeAndJoin()
        assertTrue(sensors.listeners.isEmpty(), "the orientation sensor is still registered after close")
    }

    // An accelerometer reading with gravity along x and y, as OrientationEventListener reads one.
    private fun ShadowSensorManager.tilt(sensor: Sensor, x: Float, y: Float) =
        sendSensorEventToListeners(SensorEventBuilder.newBuilder(sensor, floatArrayOf(x, y, 0f)).build())

    private companion object {
        const val GRAVITY = 9.81f
    }
}
