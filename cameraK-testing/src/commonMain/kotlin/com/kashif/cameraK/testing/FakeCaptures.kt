@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Values whose constructors are engine API, built for app tests without the opt-in. Nothing is written to disk. */
public object FakeCaptures {
    public fun image(
        path: String = "/fake/IMG_0001.jpg",
        width: Int = 4000,
        height: Int = 3000,
        format: ImageFormat = ImageFormat.JPEG,
    ): CapturedImage = CapturedImage(path = path, format = format, width = width, height = height)

    public fun video(
        path: String = "/fake/VID_0001.mp4",
        duration: Duration = 1.seconds,
        width: Int = 1920,
        height: Int = 1080,
        hasAudio: Boolean = false,
    ): RecordedVideo = RecordedVideo(
        path = path,
        duration = duration,
        width = width,
        height = height,
        bitrateBps = null,
        hasAudio = hasAudio,
    )
}
