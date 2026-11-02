package com.kashif.cameraK.controller

import android.view.OrientationEventListener
import com.kashif.cameraK.enums.DeviceOrientation
import org.junit.Test
import kotlin.test.assertEquals

/** The sensor angle buckets the device orientation follows, decided without a sensor. */
class OrientationTrackerTest {
    @Test
    fun mapsSensorAnglesToTheFourBuckets() {
        val expected = mapOf(
            0 to DeviceOrientation.PORTRAIT,
            44 to DeviceOrientation.PORTRAIT,
            315 to DeviceOrientation.PORTRAIT,
            359 to DeviceOrientation.PORTRAIT,
            45 to DeviceOrientation.LANDSCAPE_RIGHT,
            134 to DeviceOrientation.LANDSCAPE_RIGHT,
            135 to DeviceOrientation.PORTRAIT_UPSIDE_DOWN,
            224 to DeviceOrientation.PORTRAIT_UPSIDE_DOWN,
            225 to DeviceOrientation.LANDSCAPE_LEFT,
            314 to DeviceOrientation.LANDSCAPE_LEFT,
            OrientationEventListener.ORIENTATION_UNKNOWN to null,
            360 to null,
        )

        assertEquals(expected, expected.keys.associateWith(::orientationForSensorAngle))
    }
}
