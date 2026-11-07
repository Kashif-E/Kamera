package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [photo] decoded small enough for the thumb beside the shutter, off the main thread; null until it
 * is, when there is no photo, and when the file cannot be read.
 */
@Composable
internal fun rememberThumbnail(photo: CapturedImage?): State<ImageBitmap?> {
    val maxPx = with(LocalDensity.current) { THUMB_SIZE.roundToPx() }
    val inspecting = LocalInspectionMode.current
    return produceState<ImageBitmap?>(null, photo, maxPx) {
        value = null
        if (photo == null || inspecting) return@produceState
        value = withContext(Dispatchers.Default) {
            try {
                decodeThumbnail(photo.path, maxPx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.w(TAG, "Decoding the last photo's thumbnail failed", e)
                null
            }
        }
    }
}

/** The image at [path], upright and scaled so its shorter side is about [maxPx]; null when there is none. */
internal expect fun decodeThumbnail(path: String, maxPx: Int): ImageBitmap?

/** This image scaled down so its shorter side is [maxPx]; itself when it is already that small. */
internal fun ImageBitmap.scaledDown(maxPx: Int): ImageBitmap {
    val scale = maxPx.toFloat() / min(width, height)
    if (scale >= 1f) return this
    val size = IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    val scaled = ImageBitmap(size.width, size.height)
    Canvas(scaled).drawImageRect(this, dstSize = size, paint = Paint().apply { filterQuality = FilterQuality.Medium })
    return scaled
}

// Twice the thumb's 48 dp, so it stays sharp under a crop.
private val THUMB_SIZE = 96.dp
private const val TAG = "Sample"
