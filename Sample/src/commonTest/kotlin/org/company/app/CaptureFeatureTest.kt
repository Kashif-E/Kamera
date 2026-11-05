@file:OptIn(ExperimentalCoroutinesApi::class, CameraKEngineApi::class)

package org.company.app

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Sample's photo button captures through the holder (design §12.3 LC'), so the image saver,
 * which auto-saves from the holder's captures, sees every photo.
 */
class CaptureFeatureTest {
    private class FakeCapturer : SampleCapturer {
        var calls = 0
        val image = CapturedImage(path = "/fake/IMG_0001.jpg", format = ImageFormat.JPEG, width = 4, height = 3)

        override suspend fun capture(): CameraKResult<CapturedImage> {
            calls++
            return CameraKResult.Success(image)
        }
    }

    @Test
    fun photoActionCapturesThroughTheHolder() = runTest {
        val capturer = FakeCapturer()
        val feature = CaptureFeature(capturer)
        val notices = mutableListOf<String>()
        backgroundScope.launch { feature.notices.collect { notices += it } }
        runCurrent()

        feature.takePhoto()
        runCurrent()

        assertEquals(1, capturer.calls, "the photo action captured ${capturer.calls} times")
        assertEquals(capturer.image, feature.lastPhoto.value, "the screen does not show the photo")
        assertEquals(listOf("Photo saved"), notices)
    }
}
