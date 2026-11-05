@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.QualityFallback
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A video-quality query that throws, or that CameraX answers with an empty set, says nothing about
 * the camera: the engine records the requested quality with 1.x's CameraX fallback,
 * `lowerQualityOrHigherThan(requested)`, and the capabilities still report the empty set (w7 A3'
 * carry-over 2). A query that names other qualities only still refuses the request, which
 * AndroidCapabilitiesTest.qualityOutsideTheBoundCamerasSetIsUnsupportedVideoQuality pins.
 */
@RunWith(RobolectricTestRunner::class)
class VideoQualityQueryFailureTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun aThrowingQueryRecordsWithCameraXsFallback() = runTest {
        platform.backend.qualityFailure = IllegalStateException("encoder profiles unavailable")
        assertRecordsWithTheFallback()
    }

    @Test
    fun anEmptyQueryRecordsWithCameraXsFallback() = runTest {
        platform.backend.supportedQualities = emptySet()
        assertRecordsWithTheFallback()
    }

    private suspend fun TestScope.assertRecordsWithTheFallback() {
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        val config = VideoConfiguration {
            quality = VideoQuality.FHD
            enableAudio = false
        }
        val start = async { engine.startRecording(config) }
        platform.settle(testScheduler)

        assertIs<CameraKResult.Success<String>>(start.await(), "the start with an unknown quality set")
        val build = platform.backend.videoCaptureBuilds.single()
        assertEquals(VideoQuality.FHD, build.quality, "the requested quality")
        assertEquals(QualityFallback.LOWER_OR_HIGHER, build.fallback, "CameraX's fallback")
        assertEquals(emptySet(), engine.capabilities.value.supportedVideoQualities, "the reported qualities")
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }
}
