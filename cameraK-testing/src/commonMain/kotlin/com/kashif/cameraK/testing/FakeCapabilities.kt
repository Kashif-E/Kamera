package com.kashif.cameraK.testing

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.enums.ImageFormat

/** S2' red: the declaration only. */
public object FakeCapabilities {
    public const val BACK_ID: String = "back"
    public const val FRONT_ID: String = "front"

    public fun backAndFront(
        imageFormats: Set<ImageFormat> = setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.HEIC),
    ): CameraCapabilities = TODO("S2' red $imageFormats")
}
