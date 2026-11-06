package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.DeviceOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIInterfaceOrientationUnknown
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Photos take their orientation from setTargetOrientation when it is set, as the API documents. (The
 * preview follows the interface orientation: RotationTest.previewTakesTheCoordinatorPreviewAngle.)
 */
class OrientationTest {
    private val rig = CaptureRig()

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun aTargetOrientationDecidesThePhotosOrientation() {
        rig.start()

        rig.launch { setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT) }
        val (_, landscape) = rig.capture()
        assertEquals(
            AVCaptureVideoOrientationLandscapeRight,
            landscape.orientation,
            "a LANDSCAPE_LEFT target did not reach the photo",
        )

        rig.launch { setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN) }
        val (_, upsideDown) = rig.capture()
        assertEquals(
            AVCaptureVideoOrientationPortraitUpsideDown,
            upsideDown.orientation,
            "a PORTRAIT_UPSIDE_DOWN target did not reach the photo",
        )
    }

    /** Guard for the single mapping table (green before the fix as well). */
    @Test
    fun orientationsMapToTheVideoOrientationOfTheSideTheInterfaceIsOn() {
        assertEquals(
            AVCaptureVideoOrientationPortrait,
            UIDeviceOrientation.UIDeviceOrientationPortrait.toVideoOrientation(),
        )
        assertEquals(
            AVCaptureVideoOrientationPortraitUpsideDown,
            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown.toVideoOrientation(),
        )
        assertEquals(
            AVCaptureVideoOrientationLandscapeRight,
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft.toVideoOrientation(),
        )
        assertEquals(
            AVCaptureVideoOrientationLandscapeLeft,
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight.toVideoOrientation(),
        )
        assertNull(UIDeviceOrientation.UIDeviceOrientationFaceUp.toVideoOrientation())
        assertNull(UIDeviceOrientation.UIDeviceOrientationUnknown.toVideoOrientation())

        DeviceOrientation.entries.forEach { orientation ->
            val device = when (orientation) {
                DeviceOrientation.PORTRAIT -> UIDeviceOrientation.UIDeviceOrientationPortrait
                DeviceOrientation.LANDSCAPE_LEFT -> UIDeviceOrientation.UIDeviceOrientationLandscapeLeft
                DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown
                DeviceOrientation.LANDSCAPE_RIGHT -> UIDeviceOrientation.UIDeviceOrientationLandscapeRight
            }
            assertEquals(orientation, device.toDeviceOrientation())
            assertEquals(device.toVideoOrientation(), orientation.toVideoOrientation(), "$orientation maps differently")
        }

        assertEquals(AVCaptureVideoOrientationPortrait, UIInterfaceOrientationPortrait.toVideoOrientation())
        assertEquals(
            AVCaptureVideoOrientationPortraitUpsideDown,
            UIInterfaceOrientationPortraitUpsideDown.toVideoOrientation(),
        )
        assertEquals(AVCaptureVideoOrientationLandscapeLeft, UIInterfaceOrientationLandscapeLeft.toVideoOrientation())
        assertEquals(AVCaptureVideoOrientationLandscapeRight, UIInterfaceOrientationLandscapeRight.toVideoOrientation())
        assertNull(UIInterfaceOrientationUnknown.toVideoOrientation())
    }
}
