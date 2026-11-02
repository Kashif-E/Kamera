package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.QualityPrioritization
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCapturePhotoQualityPrioritizationQuality
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVVideoCodecJPEG
import platform.AVFoundation.AVVideoCodecKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The photo output allocates capture buffers for the settings it was prepared with; prepared with
 * nothing, it allocates them on the first shutter press. Once the session runs, and again after a
 * lens switch, it is prepared with the settings captures actually use.
 */
@OptIn(ExperimentalForeignApi::class)
class PreparedPhotoSettingsTest {
    private val largest = PhotoDimensions(8064, 6048)
    private val rig = CaptureRig(
        device = FakeDevice(supportedMaxPhotoDimensions = listOf(PhotoDimensions(4032, 3024), largest)),
        qualityPrioritization = QualityPrioritization.QUALITY,
    )

    @AfterTest
    fun tearDown() = rig.tearDown()

    private fun AVCapturePhotoSettings.describe() = listOf(
        format?.get(AVVideoCodecKey),
        photoQualityPrioritization,
        maxPhotoDimensions.toPhotoDimensions(),
        flashMode,
    )

    private fun preparedNonEmpty() = rig.capturer.prepareCalls.count { it.isNotEmpty() }

    @Test
    fun theRunningSessionIsPreparedWithTheSettingsCapturesUse() {
        rig.start()
        drainSessionThenMain(rig.custom)

        val prepared = rig.capturer.prepareCalls.lastOrNull().orEmpty()
        assertEquals(1, prepared.size, "the photo output was prepared with ${prepared.size} settings")
        val settings = prepared.single()
        assertEquals(AVVideoCodecJPEG, settings.format?.get(AVVideoCodecKey), "prepared for another codec")
        assertEquals(AVCapturePhotoQualityPrioritizationQuality, settings.photoQualityPrioritization)
        assertEquals(largest, settings.maxPhotoDimensions.toPhotoDimensions(), "prepared for another photo size")

        val (_, request) = rig.capture()
        assertEquals(request.settings.describe(), settings.describe(), "captures use settings it was not prepared for")
    }

    @Test
    fun aLensSwitchPreparesThePhotoOutputAgain() {
        rig.start()
        drainSessionThenMain(rig.custom)
        val before = preparedNonEmpty()

        rig.controller.toggleCameraLens()
        drainSessionThenMain(rig.custom)

        assertTrue(preparedNonEmpty() > before, "the photo output was not prepared again for the new camera")
    }
}
