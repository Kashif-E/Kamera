package com.kashif.cameraK.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

/** Turns camera frames into preview bitmaps. */
internal class PreviewFrameRenderer {
    fun render(image: BufferedImage): ImageBitmap = image.toComposeImageBitmap()

    fun close() = Unit
}
