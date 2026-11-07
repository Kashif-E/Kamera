@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Where a tap on the preview's content rectangle meters (design §12.4 "W13", the iOS point mapping).
 *
 * With a usable preview layer the content point is scaled into the layer's bounds, and the layer
 * converts that layer point, accounting for its gravity's crop, its rotation and the front camera's
 * mirroring. Without one (none adopted, empty bounds, or no connection) the engine maps the point
 * from the frame: unmirrored on the front camera, turned back by the frame rotation, then widened
 * out of the configured ratio's crop of the active format.
 */
class FocusPointOfInterestTest {
    @Test
    fun theFallbackMapsTheCornerThroughMirrorRotationAndCrop() {
        for ((lens, rotations) in CORNER_AFTER_ROTATION) {
            for ((degrees, corner) in rotations) {
                for ((ratioAndFormat, inset) in CROP_INSETS) {
                    val (ratio, format) = ratioAndFormat
                    val expected = PointOfInterest(
                        if (corner.x == 0.0) inset.x else 1 - inset.x,
                        if (corner.y == 0.0) inset.y else 1 - inset.y,
                    )
                    val mapped =
                        contentPointToDevicePoint(NormalizedPoint(0f, 0f), lens == FRONT, degrees, ratio, format)
                    assertClose(expected, mapped, "(0, 0) on the $lens camera at $degrees degrees, $ratio over $format")
                }
            }
        }
    }

    @Test
    fun theFallbackKeepsTheCentre() {
        for (mirrored in listOf(false, true)) {
            for (degrees in listOf(0, 90, 180, 270)) {
                for ((ratioAndFormat, _) in CROP_INSETS) {
                    val (ratio, format) = ratioAndFormat
                    val mapped =
                        contentPointToDevicePoint(NormalizedPoint(0.5f, 0.5f), mirrored, degrees, ratio, format)
                    assertClose(CENTRE_OF_INTEREST, mapped, "the centre, mirrored $mirrored, $degrees degrees, $ratio")
                }
            }
        }
    }

    @Test
    fun theFallbackMapsAnOffCentrePoint() {
        // Portrait back camera: (x, y) turns to (y, 1 - x); 16:9 over a 4:3 format shows 3/4 of the short side.
        val mapped =
            contentPointToDevicePoint(NormalizedPoint(0.25f, 0.1f), false, 90, AspectRatio.RATIO_16_9, FOUR_THREE)
        assertClose(PointOfInterest(0.1, 0.6875), mapped, "(0.25, 0.1)")
        @Suppress("DEPRECATION")
        val portrait =
            contentPointToDevicePoint(NormalizedPoint(0.25f, 0.1f), false, 90, AspectRatio.RATIO_9_16, FOUR_THREE)
        assertClose(PointOfInterest(0.1, 0.6875), portrait, "9:16 is 16:9")
        val unknown = contentPointToDevicePoint(NormalizedPoint(0.25f, 0.1f), false, 90, AspectRatio.RATIO_16_9, null)
        assertClose(PointOfInterest(0.1, 0.75), unknown, "an unknown format is taken as the configured ratio")
    }

    @Test
    fun withoutALayerEachOrientationMapsTheCornerOnBothCameras() {
        for ((orientation, degrees) in BACK_ROTATION) {
            val driver = AppleEngineDriver(metering = true)
            driver.deviceOrientation = orientation
            driver.runCase {
                val engine = driver.createEngine()
                driver.settle()
                assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
                assertEquals(
                    CameraKResult.Success(Unit),
                    engine.focusAt(NormalizedPoint(0f, 0f)),
                    "focusAt on the back",
                )
                assertClose(
                    CORNER_AFTER_ROTATION.getValue(BACK).getValue(degrees),
                    driver.back.lastPointOfInterest("setFocusPointOfInterest"),
                    "back camera held $orientation",
                )

                assertTrue(
                    engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)) is CameraKResult.Success,
                )
                assertEquals(
                    CameraKResult.Success(Unit),
                    engine.focusAt(NormalizedPoint(0f, 0f)),
                    "focusAt on the front",
                )
                assertClose(
                    CORNER_AFTER_ROTATION.getValue(FRONT).getValue(FRONT_ROTATION.getValue(orientation)),
                    driver.front.lastPointOfInterest("setExposurePointOfInterest"),
                    "front camera held $orientation",
                )
                engine.closeAndJoin()
            }
        }
    }

    @Test
    fun aLayerConvertsTheContentPointScaledIntoItsBounds() {
        val layerPoints = mutableListOf<Pair<Double, Double>>()
        val driver = AppleEngineDriver(metering = true)
        driver.previewPointConverting = PreviewPointConverter { _, x, y ->
            layerPoints += x to y
            PointOfInterest(0.25, 0.75)
        }
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val layer = AVCaptureVideoPreviewLayer()
            layer.setBounds(CGRectMake(0.0, 0.0, 300.0, 300.0))
            driver.custom.adoptPreviewLayer(layer)

            assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.1f, 0.9f)))
            assertCloseTo(30.0 to 270.0, layerPoints.single(), "the layer point in a 300 x 300 layer (1:1)")
            assertClose(
                PointOfInterest(0.25, 0.75),
                driver.back.lastPointOfInterest("setFocusPointOfInterest"),
                "focus",
            )
            assertClose(
                PointOfInterest(0.25, 0.75),
                driver.back.lastPointOfInterest("setExposurePointOfInterest"),
                "exposure",
            )

            layer.setBounds(CGRectMake(0.0, 0.0, 300.0, 1600.0 / 3))
            assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.1f, 0.9f)))
            assertCloseTo(
                30.0 to 480.0,
                layerPoints.last(),
                "the layer point in a 300 x 533.33 layer (16:9 in portrait)",
            )
            engine.closeAndJoin()
        }
    }

    @Test
    fun aLayerWithoutAConnectionOrBoundsTakesTheFallback() {
        val driver = AppleEngineDriver(metering = true)
        driver.runCase {
            val engine = driver.createEngine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            // A layer over no session has no connection to convert through.
            val layer = AVCaptureVideoPreviewLayer()
            layer.setBounds(CGRectMake(0.0, 0.0, 300.0, 400.0))
            driver.custom.adoptPreviewLayer(layer)
            assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0f, 0f)))
            assertClose(
                PointOfInterest(0.0, 1.0),
                driver.back.lastPointOfInterest("setFocusPointOfInterest"),
                "no connection: the portrait back camera's fallback",
            )
            engine.closeAndJoin()
        }

        val converted = mutableListOf<Unit>()
        val withSeam = AppleEngineDriver(metering = true)
        withSeam.previewPointConverting = PreviewPointConverter { _, _, _ ->
            converted += Unit
            PointOfInterest(0.25, 0.75)
        }
        withSeam.runCase {
            val engine = withSeam.createEngine()
            withSeam.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val layer = AVCaptureVideoPreviewLayer()
            layer.setBounds(CGRectMake(0.0, 0.0, 0.0, 0.0))
            withSeam.custom.adoptPreviewLayer(layer)
            assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0f, 0f)))
            assertEquals(emptyList(), converted, "a layer with empty bounds was asked to convert")
            assertClose(
                PointOfInterest(0.0, 1.0),
                withSeam.back.lastPointOfInterest("setFocusPointOfInterest"),
                "empty bounds: the fallback",
            )
            engine.closeAndJoin()
        }
    }

    @Test
    fun anOrientationChangeTurnsTheFallback() {
        val driver = AppleEngineDriver(metering = true)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.deviceOrientation = UIDeviceOrientation.UIDeviceOrientationLandscapeLeft
            NSNotificationCenter.defaultCenter.postNotificationName(UIDeviceOrientationDidChangeNotification, null)
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0f, 0f)))
            assertClose(
                PointOfInterest(0.0, 0.0),
                driver.back.lastPointOfInterest("setFocusPointOfInterest"),
                "the back camera turned left shows the buffer as it is",
            )
            engine.closeAndJoin()
        }
    }

    private fun FakeDevice.lastPointOfInterest(op: String): PointOfInterest =
        callsOf(op).lastOrNull()?.value as? PointOfInterest ?: fail("the device received no $op: $calls")

    private fun assertClose(expected: PointOfInterest, actual: PointOfInterest, what: String) = assertTrue(
        abs(expected.x - actual.x) < 1e-6 && abs(expected.y - actual.y) < 1e-6,
        "$what: expected $expected, was $actual",
    )

    private fun assertCloseTo(expected: Pair<Double, Double>, actual: Pair<Double, Double>, what: String) = assertTrue(
        abs(expected.first - actual.first) < 1e-3 && abs(expected.second - actual.second) < 1e-3,
        "$what: expected $expected, was $actual",
    )

    private companion object {
        const val BACK = "back"
        const val FRONT = "front"
        val FOUR_THREE = PhotoDimensions(4032, 3024)
        val SIXTEEN_NINE = PhotoDimensions(3840, 2160)

        // Where (0, 0) lands once the front camera's mirror is undone and the frame rotation is turned back.
        val CORNER_AFTER_ROTATION = mapOf(
            BACK to mapOf(
                0 to PointOfInterest(0.0, 0.0),
                90 to PointOfInterest(0.0, 1.0),
                180 to PointOfInterest(1.0, 1.0),
                270 to PointOfInterest(1.0, 0.0),
            ),
            FRONT to mapOf(
                0 to PointOfInterest(1.0, 0.0),
                90 to PointOfInterest(0.0, 0.0),
                180 to PointOfInterest(0.0, 1.0),
                270 to PointOfInterest(1.0, 1.0),
            ),
        )

        // How far a corner moves in from each edge of the buffer: the crop the configured ratio makes of the format.
        val CROP_INSETS = mapOf(
            (AspectRatio.RATIO_4_3 to FOUR_THREE) to PointOfInterest(0.0, 0.0),
            (AspectRatio.RATIO_16_9 to FOUR_THREE) to PointOfInterest(0.0, 0.125),
            (AspectRatio.RATIO_1_1 to FOUR_THREE) to PointOfInterest(0.125, 0.0),
            (AspectRatio.RATIO_4_3 to SIXTEEN_NINE) to PointOfInterest(0.125, 0.0),
            (AspectRatio.RATIO_16_9 to SIXTEEN_NINE) to PointOfInterest(0.0, 0.0),
            (AspectRatio.RATIO_1_1 to SIXTEEN_NINE) to PointOfInterest(0.21875, 0.0),
        )

        // The frame rotation EngineRotation gives each way the device is held.
        val BACK_ROTATION = mapOf(
            UIDeviceOrientation.UIDeviceOrientationPortrait to 90,
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft to 0,
            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown to 270,
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight to 180,
        )
        val FRONT_ROTATION = mapOf(
            UIDeviceOrientation.UIDeviceOrientationPortrait to 270,
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft to 180,
            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown to 90,
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight to 0,
        )
    }
}
