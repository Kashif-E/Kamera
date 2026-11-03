@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The orientation sensor runs only between start and stop: it starts with start, stops with stop and
 * with close, and a callback that arrives after close changes nothing. Replaces
 * AndroidLegacySessionTest.lateOrientationCallbackAfterCloseDoesNothing.
 */
@RunWith(RobolectricTestRunner::class)
class OrientationGatingTest {
    private val platform = FakeAndroidPlatform()
    private val sensorManager = platform.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensors = shadowOf(sensorManager)
    private val accelerometer: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER).also(sensors::addSensor)

    @Test
    fun sensorRunsBetweenStartAndStopAndALateCallbackDoesNothing() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertTrue(sensors.listeners.isEmpty(), "the sensor runs before start")

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        assertEquals(1, sensors.listeners.size, "the sensor does not run after start")
        tilt(x = -GRAVITY, y = 0f)
        assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.deviceOrientation.value, "while started")

        engine.stop()
        assertTrue(sensors.listeners.isEmpty(), "the sensor still runs after stop")

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        val late = sensors.listeners.single()
        engine.closeAndJoin()
        assertTrue(sensors.listeners.isEmpty(), "the sensor still runs after close")

        late.onSensorChanged(SensorEventBuilder.newBuilder(accelerometer, floatArrayOf(0f, GRAVITY, 0f)).build())
        assertEquals(
            DeviceOrientation.LANDSCAPE_RIGHT,
            engine.deviceOrientation.value,
            "an orientation callback after close changed deviceOrientation",
        )
    }

    // An accelerometer reading with gravity along x and y, as OrientationEventListener reads one.
    private fun tilt(x: Float, y: Float) =
        sensors.sendSensorEventToListeners(SensorEventBuilder.newBuilder(accelerometer, floatArrayOf(x, y, 0f)).build())

    private companion object {
        const val GRAVITY = 9.81f
    }
}
