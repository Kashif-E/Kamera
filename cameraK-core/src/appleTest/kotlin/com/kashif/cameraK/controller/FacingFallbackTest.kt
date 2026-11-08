package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.QualityPrioritization
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTelephotoCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A device type the requested facing lacks opens that facing's default camera, with a warning, and
 * never a camera facing the other way, as Android's CameraSelectorResolver does. On an iPhone 14 Pro a
 * FRONT TELEPHOTO or ULTRA_WIDE request opened the back telephoto or ultra-wide (device matrix #1294).
 */
class FacingFallbackTest {
    private val frontWide = FakeDevice(isFront = true, uniqueId = "front-wide")
    private val backWide = FakeDevice(isFront = false, uniqueId = "back-wide")
    private val backTelephoto = FakeDevice(isFront = false, uniqueId = "back-telephoto")
    private val backUltraWide = FakeDevice(isFront = false, uniqueId = "back-ultra-wide")

    /** An iPhone 14 Pro: a wide, a telephoto and an ultra-wide on the back, a wide-angle on the front. */
    private val iPhone14Pro = provider(
        Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, backWide),
        Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInTelephotoCamera!!, backTelephoto),
        Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInUltraWideCamera!!, backUltraWide),
        Triple(AVCaptureDevicePositionFront, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, frontWide),
    )

    private val controllers = mutableListOf<CustomCameraController>()

    @AfterTest
    fun tearDown() {
        controllers.forEach { it.cleanupSession() }
    }

    @Test
    fun aFrontTypeTheFrontLacksOpensTheFrontDefaultWithAWarning() {
        val controller = controller(iPhone14Pro)
        capturingLogs { logs ->
            for (type in listOf(CameraDeviceType.TELEPHOTO, CameraDeviceType.ULTRA_WIDE)) {
                assertSame(frontWide, controller.findCamera(CameraLens.FRONT, type), "a FRONT $type request")
            }
            val warnings = logs().filter { it.startsWith("WARN") }
            assertEquals(2, warnings.size, "one warning per missing type: ${logs()}")
            assertTrue(warnings.all { "FRONT" in it }, "the warnings name the facing: $warnings")
        }
    }

    @Test
    fun aTypeTheFacingHasIsThatCamera() {
        val controller = controller(iPhone14Pro)
        assertSame(backTelephoto, controller.findCamera(CameraLens.BACK, CameraDeviceType.TELEPHOTO))
        assertSame(backUltraWide, controller.findCamera(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE))
        assertSame(frontWide, controller.findCamera(CameraLens.FRONT, CameraDeviceType.WIDE_ANGLE))
    }

    @Test
    fun aFacingWithNoCameraAtAllOpensNoCameraOfTheOtherFacing() {
        val backOnly = provider(
            Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, backWide),
            Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInTelephotoCamera!!, backTelephoto),
        )
        assertNull(controller(backOnly).findCamera(CameraLens.FRONT, CameraDeviceType.TELEPHOTO))
    }

    @Test
    fun setupForAFrontTypeTheFrontLacksBindsTheFrontDefault() {
        val session = FakeSessionOps()
        val controller = controller(iPhone14Pro, session)
        var failure: Any? = "not run"
        controller.afterQueuedSessionWork {
            failure = controller.configureOnQueue(null, CameraLens.FRONT, CameraDeviceType.TELEPHOTO)
        }
        drainSessionThenMain(controller)

        assertNull(failure, "setup failed")
        assertSame(frontWide, controller.currentDevice, "the camera setup bound")
        assertEquals(listOf(frontWide.input), session.inputs, "the session's camera input")
    }

    /** A discovery that knows [cameras]. */
    private fun provider(vararg cameras: Triple<AVCaptureDevicePosition, String, FakeDevice>) =
        DiscoveryCameraDeviceProvider(
            discover = { types, position ->
                cameras.filter { (at, type, _) ->
                    (position == platform.AVFoundation.AVCaptureDevicePositionUnspecified || at == position) &&
                        type in types
                }.map { (_, type, camera) -> DiscoveredCamera(type, camera) }
            },
            defaultCamera = { null },
            cameraWithId = { null },
        )

    private fun controller(devices: CameraDeviceProvider, session: FakeSessionOps = FakeSessionOps()) =
        CustomCameraController(
            qualityPrioritization = QualityPrioritization.BALANCED,
            sessionFactory = { session },
            cameraDevices = devices,
            photoCapturer = FakePhotoCapturer(),
        ).also { controllers += it }
}
