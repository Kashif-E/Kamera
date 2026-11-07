@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality

/** Capabilities for [FakeCameraEngine], built without the opt-in their constructor needs. */
public object FakeCapabilities {
    /** The back camera's id in [backAndFront]. */
    public const val BACK_ID: String = "back"

    /** The front camera's id in [backAndFront]. */
    public const val FRONT_ID: String = "front"

    private const val EV_STEP = 1f / 3
    private val exposureRanges = mapOf(BACK_ID to -6..6, FRONT_ID to -3..3)

    /**
     * A back camera with a flash unit and zoom 1 to 10, and a front camera without one and zoom 1 to
     * 4. Everything else is supported: pause and resume, every video quality and bitrates, every
     * aspect ratio and square capture, both analysis formats, and [imageFormats].
     *
     * With [focusAndExposure], the back camera meters at a focus point and takes exposure indexes
     * -6 to 6, and the fixed-focus front camera takes -3 to 3, both in steps of a third of an EV.
     * Without it, neither camera supports either.
     */
    public fun backAndFront(
        imageFormats: Set<ImageFormat> = setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.HEIC),
        focusAndExposure: Boolean = true,
    ): CameraCapabilities {
        val back = LensInfo(
            id = BACK_ID,
            deviceType = CameraDeviceType.WIDE_ANGLE,
            lens = CameraLens.BACK,
            minZoom = 1f,
            maxZoom = 10f,
            hasFlash = true,
            isLogical = false,
        )
        val front = LensInfo(
            id = FRONT_ID,
            deviceType = CameraDeviceType.WIDE_ANGLE,
            lens = CameraLens.FRONT,
            minZoom = 1f,
            maxZoom = 4f,
            hasFlash = false,
            isLogical = false,
        )
        return CameraCapabilities(
            allLenses = listOf(back, front),
            flashModes = mapOf(
                BACK_ID to setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON),
                FRONT_ID to setOf(FlashMode.OFF),
            ),
            torchModes = mapOf(BACK_ID to setOf(TorchMode.OFF, TorchMode.ON), FRONT_ID to setOf(TorchMode.OFF)),
            supportsPauseResume = true,
            supportedVideoQualities = VideoQuality.entries.toSet(),
            supportsVideoBitrate = true,
            supportedAspectRatios = AspectRatio.entries.toSet(),
            supportedImageFormats = imageFormats,
            supportedAnalysisFormats = FrameFormat.entries.toSet(),
            supportsSquareCapture = true,
            focusPointCameras = if (focusAndExposure) setOf(BACK_ID) else emptySet(),
            exposureCompensationRanges = if (focusAndExposure) exposureRanges else emptyMap(),
            exposureCompensationSteps = if (focusAndExposure) exposureRanges.mapValues { EV_STEP } else emptyMap(),
        )
    }
}
