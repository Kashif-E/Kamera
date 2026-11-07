package org.company.app

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.File

@OptIn(ExperimentalResourceApi::class)
internal actual fun decodeThumbnail(path: String, maxPx: Int): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    return file.readBytes().decodeToImageBitmap().scaledDown(maxPx)
}
