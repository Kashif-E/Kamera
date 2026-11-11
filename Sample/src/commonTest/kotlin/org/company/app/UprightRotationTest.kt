package org.company.app

import com.kashif.cameraK.enums.DeviceOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The capture controls' icons turn against the device so they stay upright, but only by what the
 * screen has not turned already: the Android Sample turns with the phone, and its icons were drawn
 * sideways in the sideways layout.
 */
class UprightRotationTest {
    @Test
    fun aScreenThatTurnedWithTheDeviceTurnsNoIcon() {
        for (orientation in DeviceOrientation.entries) {
            assertEquals(0f, uprightRotation(device = orientation, screen = orientation), "held $orientation")
        }
    }

    @Test
    fun aScreenThatStayedUprightTurnsTheIconsAgainstTheDevice() {
        for (orientation in DeviceOrientation.entries) {
            assertEquals(
                orientation.compensationDegrees,
                uprightRotation(device = orientation, screen = DeviceOrientation.PORTRAIT),
                "held $orientation",
            )
        }
    }

    @Test
    fun aDeviceTurnedFromOneLandscapeToTheOtherBeforeTheScreenFollowsTurnsTheIconsHalfWay() {
        assertEquals(
            180f,
            uprightRotation(device = DeviceOrientation.LANDSCAPE_RIGHT, screen = DeviceOrientation.LANDSCAPE_LEFT),
        )
    }
}
