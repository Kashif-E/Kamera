package com.kashif.cameraK.testing

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.enums.ImageFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** S2' red: the declaration only. */
public object FakeCaptures {
    public fun image(
        path: String = "/fake/IMG_0001.jpg",
        width: Int = 4000,
        height: Int = 3000,
        format: ImageFormat = ImageFormat.JPEG,
    ): CapturedImage = TODO("S2' red $path $width $height $format")

    public fun video(
        path: String = "/fake/VID_0001.mp4",
        duration: Duration = 1.seconds,
        width: Int = 1920,
        height: Int = 1080,
        hasAudio: Boolean = false,
    ): RecordedVideo = TODO("S2' red $path $duration $width $height $hasAudio")
}
