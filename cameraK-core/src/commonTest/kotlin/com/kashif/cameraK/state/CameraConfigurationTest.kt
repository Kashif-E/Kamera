package com.kashif.cameraK.state

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The configuration DSL (design §6.1, CC-21, CC-28): every default comes from [CameraDefaults],
 * `copy { }` changes only what it sets, and equality, hashing and `toString` cover every field.
 */
class CameraConfigurationTest {
    @Test
    fun dslDefaultsEqualCameraDefaults() {
        val config = CameraConfiguration {}

        assertEquals(CameraDefaults.LENS, config.cameraLens, "cameraLens")
        assertEquals(CameraDefaults.DEVICE_TYPE, config.cameraDeviceType, "cameraDeviceType")
        assertEquals(CameraDefaults.CAMERA_ID, config.cameraId, "cameraId")
        assertEquals(CameraDefaults.FLASH, config.flashMode, "flashMode")
        assertEquals(CameraDefaults.TORCH, config.torchMode, "torchMode")
        assertEquals(CameraDefaults.ZOOM_RATIO, config.initialZoomRatio, "initialZoomRatio")
        assertEquals(CameraDefaults.IMAGE_FORMAT, config.imageFormat, "imageFormat")
        assertEquals(CameraDefaults.QUALITY, config.qualityPrioritization, "qualityPrioritization")
        assertEquals(CameraDefaults.ASPECT_RATIO, config.aspectRatio, "aspectRatio")
        assertEquals<Any?>(CameraDefaults.TARGET_RESOLUTION, config.targetResolution, "targetResolution")
        assertEquals(CameraDefaults.TARGET_ORIENTATION, config.targetOrientation, "targetOrientation")
        assertEquals(CameraDefaults.OUTPUT_DIRECTORY, config.outputDirectory, "outputDirectory")
        assertEquals(CameraDefaults.MIRROR_FRONT_CAMERA, config.mirrorFrontCamera, "mirrorFrontCamera")
        assertEquals(CameraDefaults.FRAME_ANALYSIS, config.frameAnalysis, "frameAnalysis")
        assertEquals(CameraDefaults.ANALYSIS_RESOLUTION, config.analysisResolution, "analysisResolution")
        assertEquals(CameraDefaults.ANALYSIS_FORMAT, config.analysisFormat, "analysisFormat")
        // The platform default camera, and the 16:9 every rememberCameraKState user already gets.
        assertEquals(null, CameraDefaults.LENS)
        assertEquals(AspectRatio.RATIO_16_9, CameraDefaults.ASPECT_RATIO)
    }

    @Test
    fun copyKeepsUnsetFields() {
        val original = CameraConfiguration {
            cameraLens = CameraLens.FRONT
            flashMode = FlashMode.ON
            aspectRatio = AspectRatio.RATIO_4_3
            outputDirectory = "/app/files/shots"
            analysisResolution = Resolution(640, 480)
        }

        val copied = original.copy { flashMode = FlashMode.AUTO }

        assertEquals(
            CameraConfiguration {
                cameraLens = CameraLens.FRONT
                flashMode = FlashMode.AUTO
                aspectRatio = AspectRatio.RATIO_4_3
                outputDirectory = "/app/files/shots"
                analysisResolution = Resolution(640, 480)
            },
            copied,
        )
        assertEquals(FlashMode.ON, original.flashMode, "copy changed the original")
        assertEquals(original, original.copy {}, "an empty copy is not equal to its original")
    }

    @Test
    fun equalsAndHashCodeCoverEveryField() {
        val defaults = CameraConfiguration {}
        // One change per field, each away from its default. directory is not among them: 2.0 has no such field.
        val changes: Map<String, CameraConfiguration.Builder.() -> Unit> = mapOf(
            "cameraLens" to { cameraLens = CameraLens.FRONT },
            "cameraDeviceType" to { cameraDeviceType = CameraDeviceType.TELEPHOTO },
            "cameraId" to { cameraId = "/dev/video2" },
            "flashMode" to { flashMode = FlashMode.AUTO },
            "torchMode" to { torchMode = TorchMode.ON },
            "initialZoomRatio" to { initialZoomRatio = 2f },
            "imageFormat" to { imageFormat = ImageFormat.PNG },
            "qualityPrioritization" to { qualityPrioritization = QualityPrioritization.SPEED },
            "aspectRatio" to { aspectRatio = AspectRatio.RATIO_1_1 },
            "targetResolution" to { targetResolution = Resolution(1920, 1080) },
            "targetOrientation" to { targetOrientation = DeviceOrientation.LANDSCAPE_LEFT },
            "outputDirectory" to { outputDirectory = "/app/files/shots" },
            "mirrorFrontCamera" to { mirrorFrontCamera = true },
            "frameAnalysis" to { frameAnalysis = true },
            "analysisResolution" to { analysisResolution = Resolution(640, 480) },
            "analysisFormat" to { analysisFormat = FrameFormat.RGBA_8888 },
        )

        for ((field, change) in changes) {
            val changed = CameraConfiguration(change)
            assertNotEquals(defaults, changed, "equals ignores $field")
            assertNotEquals(defaults.hashCode(), changed.hashCode(), "hashCode ignores $field")
            assertEquals(changed, CameraConfiguration(change), "two configurations with the same $field differ")
            assertEquals(changed.hashCode(), CameraConfiguration(change).hashCode(), "hashCode of the same $field")
            assertTrue(field in changed.toString(), "toString omits $field: $changed")
        }
    }
}
