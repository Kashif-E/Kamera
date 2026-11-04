package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTelephotoCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Which camera CameraDeviceType.DEFAULT opens (issue #170, PR #175). The iPhone 17 family's front
 * camera, a square sensor for Center Stage, is a built-in ultra-wide camera to AVFoundation, and the
 * wide-angle-only DEFAULT squashed its preview. On the front, DEFAULT tries the built-in ultra-wide
 * first and then the wide-angle; on the back it stays the wide-angle. The discovery session promises
 * no order, so the provider applies the preference itself.
 */
class CameraDeviceProviderTest {
    private val frontWide = FakeDevice(isFront = true, uniqueId = "front-wide")
    private val frontUltraWide = FakeDevice(isFront = true, uniqueId = "front-ultra-wide")
    private val backWide = FakeDevice(isFront = false, uniqueId = "back-wide")
    private val backUltraWide = FakeDevice(isFront = false, uniqueId = "back-ultra-wide")
    private val backTelephoto = FakeDevice(isFront = false, uniqueId = "back-telephoto")

    /** A discovery that knows [cameras], listed in an order that is not the preference. */
    private fun provider(vararg cameras: Triple<AVCaptureDevicePosition, String, FakeDevice>) =
        DiscoveryCameraDeviceProvider(
            discover = { types, position ->
                cameras.filter { (at, type, _) -> at == position && type in types }
                    .map { (_, type, camera) -> DiscoveredCamera(type, camera) }
            },
            defaultCamera = { null },
            cameraWithId = { null },
        )

    @Test
    fun frontDefaultTriesTheUltraWideFirstThenTheWideAngle() {
        val iPhone17 = provider(
            Triple(AVCaptureDevicePositionFront, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, frontWide),
            Triple(AVCaptureDevicePositionFront, AVCaptureDeviceTypeBuiltInUltraWideCamera!!, frontUltraWide),
        )
        assertSame(
            frontUltraWide,
            iPhone17.find(CameraLens.FRONT, CameraDeviceType.DEFAULT),
            "the front DEFAULT camera where the front camera is an ultra-wide",
        )

        val earlierIPhone = provider(
            Triple(AVCaptureDevicePositionFront, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, frontWide),
        )
        assertSame(
            frontWide,
            earlierIPhone.find(CameraLens.FRONT, CameraDeviceType.DEFAULT),
            "the front DEFAULT camera where the front camera is a wide-angle",
        )
    }

    @Test
    fun backDefaultStaysTheWideAngle() {
        val tripleCamera = provider(
            Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInUltraWideCamera!!, backUltraWide),
            Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInTelephotoCamera!!, backTelephoto),
            Triple(AVCaptureDevicePositionBack, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, backWide),
        )
        assertSame(backWide, tripleCamera.find(CameraLens.BACK, CameraDeviceType.DEFAULT))
        assertSame(backUltraWide, tripleCamera.find(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE))
    }

    @Test
    fun anExplicitFrontWideAngleIsTheWideAngle() {
        val iPhone17 = provider(
            Triple(AVCaptureDevicePositionFront, AVCaptureDeviceTypeBuiltInUltraWideCamera!!, frontUltraWide),
            Triple(AVCaptureDevicePositionFront, AVCaptureDeviceTypeBuiltInWideAngleCamera!!, frontWide),
        )
        assertSame(frontWide, iPhone17.find(CameraLens.FRONT, CameraDeviceType.WIDE_ANGLE))
    }
}
