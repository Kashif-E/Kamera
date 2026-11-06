package com.kashif.cameraK.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

public fun ImageBitmap.toByteArray(): ByteArray? {
    val skiaBitmap = this.asSkiaBitmap()
    val skiaImage: Image = Image.makeFromBitmap(skiaBitmap)

    val encodedData: Data? = skiaImage.encodeToData(quality = 100)
    return encodedData?.bytes
}
