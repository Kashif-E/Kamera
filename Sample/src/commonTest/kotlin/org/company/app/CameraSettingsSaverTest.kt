package org.company.app

import androidx.compose.runtime.saveable.SaverScope
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The camera screen's configuration settings survive a rotation through rememberSaveable, so the
 * recreated screen builds an equal configuration and the retained holder, with any recording, stays.
 */
class CameraSettingsSaverTest {
    // What an Android Bundle can hold: the saver must reduce a setting to these.
    private val bundleScope = SaverScope { it is String || it is Int || it is List<*> }

    private fun roundTrip(settings: CameraSettings): CameraSettings {
        val saved = assertNotNull(with(CameraSettingsSaver) { bundleScope.save(settings) }, "nothing was saved")
        return assertNotNull(CameraSettingsSaver.restore(saved), "nothing was restored from $saved")
    }

    @Test
    fun restoresEverySetting() {
        val defaults = CameraSettings()
        val changed = CameraSettings(
            aspectRatio = AspectRatio.RATIO_16_9,
            resolution = 1920 to 1080,
            imageFormat = ImageFormat.PNG,
            qualityPrioritization = QualityPrioritization.QUALITY,
            cameraDeviceType = CameraDeviceType.TELEPHOTO,
        )
        assertEquals(defaults, roundTrip(defaults), "the defaults, with no resolution")
        assertEquals(changed, roundTrip(changed), "every setting changed, with a resolution")
        // Each setting on its own, so one that is dropped cannot hide behind another.
        listOf(
            defaults.copy(aspectRatio = AspectRatio.RATIO_9_16),
            defaults.copy(resolution = 640 to 480),
            defaults.copy(imageFormat = ImageFormat.HEIC),
            defaults.copy(qualityPrioritization = QualityPrioritization.SPEED),
            defaults.copy(cameraDeviceType = CameraDeviceType.ULTRA_WIDE),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }
}
