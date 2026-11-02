package com.kashif.cameraK.capture

import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/** The library-produced value types compare by value, so a StateFlow of them drops repeats. */
@OptIn(CameraKEngineApi::class)
class ContractValueTypesTest {
    @Test
    fun valueTypesCompareByValue() {
        assertValueEquality(
            { CapturedImage("/app/IMG_1.jpg", ImageFormat.JPEG, 4032, 3024) },
            CapturedImage("/app/IMG_1.jpg", ImageFormat.PNG, 4032, 3024),
        )
        assertValueEquality(
            { RecordedVideo("/app/VID_1.mp4", 3.seconds, 1920, 1080, 8_000_000, hasAudio = true) },
            RecordedVideo("/app/VID_1.mp4", 3.seconds, 1920, 1080, 8_000_000, hasAudio = false),
        )
        assertValueEquality({ ZoomState(2f, 0.5f, 10f) }, ZoomState(2f, 1f, 10f))
        assertValueEquality({ Resolution(1280, 720) }, Resolution(720, 1280))
        assertValueEquality(
            { GalleryAsset("IMG_1.jpg", payload = "/home/me/Pictures/CameraK/IMG_1.jpg") },
            GalleryAsset("IMG_1.jpg", payload = "/home/me/Pictures/CameraK/IMG_2.jpg"),
        )
    }

    private fun <T : Any> assertValueEquality(build: () -> T, different: T) {
        val a = build()
        val b = build()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "equal values must hash alike")
        assertNotEquals(a, different)
    }
}
