package com.kashif.cameraK.error

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four vocabularies that grow in minor releases are value classes with companion constants. Each
 * constant is distinct and prints its own name, as an enum entry would.
 */
class OpenVocabularyTest {
    @Test
    fun cameraFeatureConstantsAreDistinctAndPrintTheirName() = assertNamed(
        mapOf(
            "FLASH_AUTO" to CameraFeature.FLASH_AUTO,
            "FLASH_ON" to CameraFeature.FLASH_ON,
            "TORCH" to CameraFeature.TORCH,
            "ZOOM" to CameraFeature.ZOOM,
            "LENS_SWITCH" to CameraFeature.LENS_SWITCH,
            "DEVICE_TYPE" to CameraFeature.DEVICE_TYPE,
            "ASPECT_RATIO" to CameraFeature.ASPECT_RATIO,
            "TARGET_RESOLUTION" to CameraFeature.TARGET_RESOLUTION,
            "IMAGE_FORMAT" to CameraFeature.IMAGE_FORMAT,
            "VIDEO_QUALITY" to CameraFeature.VIDEO_QUALITY,
            "VIDEO_BITRATE" to CameraFeature.VIDEO_BITRATE,
            "PAUSE_RESUME" to CameraFeature.PAUSE_RESUME,
            "AUDIO" to CameraFeature.AUDIO,
            "TARGET_ORIENTATION" to CameraFeature.TARGET_ORIENTATION,
            "ANALYSIS_FORMAT" to CameraFeature.ANALYSIS_FORMAT,
            "PLATFORM_VERSION" to CameraFeature.PLATFORM_VERSION,
            "FOCUS_POINT" to CameraFeature.FOCUS_POINT,
            "EXPOSURE_COMPENSATION" to CameraFeature.EXPOSURE_COMPENSATION,
        ),
    )

    @Test
    fun cameraOperationConstantsAreDistinctAndPrintTheirName() = assertNamed(
        mapOf(
            "START" to CameraOperation.START,
            "CAPTURE" to CameraOperation.CAPTURE,
            "RECORD_PREPARE" to CameraOperation.RECORD_PREPARE,
            "RECORD_START" to CameraOperation.RECORD_START,
            "RECORD_STOP" to CameraOperation.RECORD_STOP,
            "RECORD_PAUSE" to CameraOperation.RECORD_PAUSE,
            "RECORD_RESUME" to CameraOperation.RECORD_RESUME,
            "ZOOM" to CameraOperation.ZOOM,
            "FLASH" to CameraOperation.FLASH,
            "TORCH" to CameraOperation.TORCH,
            "LENS" to CameraOperation.LENS,
            "DEVICE_TYPE" to CameraOperation.DEVICE_TYPE,
            "ORIENTATION" to CameraOperation.ORIENTATION,
            "FOCUS_POINT" to CameraOperation.FOCUS_POINT,
            "EXPOSURE_COMPENSATION" to CameraOperation.EXPOSURE_COMPENSATION,
        ),
    )

    @Test
    fun interruptionReasonConstantsAreDistinctAndPrintTheirName() = assertNamed(
        mapOf(
            "APP_IN_BACKGROUND" to InterruptionReason.APP_IN_BACKGROUND,
            "MULTITASKING" to InterruptionReason.MULTITASKING,
            "IN_USE_BY_ANOTHER_CLIENT" to InterruptionReason.IN_USE_BY_ANOTHER_CLIENT,
            "AUDIO_IN_USE" to InterruptionReason.AUDIO_IN_USE,
            "SYSTEM_PRESSURE" to InterruptionReason.SYSTEM_PRESSURE,
            "MEDIA_SERVICES_RESET" to InterruptionReason.MEDIA_SERVICES_RESET,
            "OTHER" to InterruptionReason.OTHER,
        ),
    )

    @Test
    fun storageFailureConstantsAreDistinctAndPrintTheirName() = assertNamed(
        mapOf(
            "FULL" to StorageFailure.FULL,
            "NOT_WRITABLE" to StorageFailure.NOT_WRITABLE,
            "NOT_FOUND" to StorageFailure.NOT_FOUND,
            "GALLERY_IMPORT_FAILED" to StorageFailure.GALLERY_IMPORT_FAILED,
        ),
    )

    @Test
    fun cameraOperationHasDeviceType() {
        assertEquals("DEVICE_TYPE", CameraOperation.DEVICE_TYPE.toString())
        assertEquals(CameraOperation.DEVICE_TYPE, CameraOperation.DEVICE_TYPE)
        kotlin.test.assertNotEquals(CameraOperation.LENS, CameraOperation.DEVICE_TYPE)
    }

    private fun assertNamed(constants: Map<String, Any>) {
        assertEquals(constants.size, constants.values.toSet().size, "two constants are equal: ${constants.keys}")
        constants.forEach { (name, constant) -> assertEquals(name, constant.toString()) }
    }
}
