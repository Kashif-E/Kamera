package com.kashif.cameraK.video

import com.kashif.cameraK.video.VideoQuality.FHD
import com.kashif.cameraK.video.VideoQuality.HD
import com.kashif.cameraK.video.VideoQuality.SD
import com.kashif.cameraK.video.VideoQuality.UHD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The quality a recording records on a camera that records a given set: the configured one when the
 * camera records it; a quality left at its default gives way to the nearest the camera records, the
 * next lower one first; a quality the app set and the camera lacks is refused (null).
 */
class QualityToRecordTest {
    @Test
    fun aQualityTheCameraRecordsIsRecordedAsConfigured() {
        assertEquals(FHD, VideoConfiguration().qualityToRecord(setOf(HD, FHD, UHD)))
        assertEquals(SD, VideoConfiguration { quality = SD }.qualityToRecord(setOf(SD, FHD)))
    }

    @Test
    fun theDefaultQualityGivesWayToTheNearestLowerOne() {
        assertEquals(HD, VideoConfiguration().qualityToRecord(setOf(SD, HD, UHD)))
    }

    @Test
    fun theDefaultQualityRisesWhenTheCameraRecordsNothingLower() {
        assertEquals(UHD, VideoConfiguration().qualityToRecord(setOf(UHD)))
    }

    @Test
    fun aQualityTheAppSetIsRefusedWhenTheCameraLacksIt() {
        assertNull(VideoConfiguration { quality = FHD }.qualityToRecord(setOf(HD)))
        assertNull(VideoConfiguration().copy { quality = UHD }.qualityToRecord(setOf(HD, FHD)))
    }

    @Test
    fun aCopyKeepsWhetherTheQualityWasSet() {
        assertEquals(HD, VideoConfiguration().copy { enableAudio = true }.qualityToRecord(setOf(HD)))
        assertNull(VideoConfiguration { quality = FHD }.copy { enableAudio = true }.qualityToRecord(setOf(HD)))
    }

    @Test
    fun aCameraThatRecordsNothingKnownRecordsNothing() {
        assertNull(VideoConfiguration().qualityToRecord(emptySet()))
    }

    @Test
    fun theDefaultQualityAndTheSameQualitySetAreDifferentConfigurations() {
        assertNotEquals(VideoConfiguration(), VideoConfiguration { quality = FHD })
        assertEquals(VideoConfiguration(), VideoConfiguration { enableAudio = false })
        assertEquals(VideoConfiguration { quality = FHD }, VideoConfiguration().copy { quality = FHD })
    }
}
