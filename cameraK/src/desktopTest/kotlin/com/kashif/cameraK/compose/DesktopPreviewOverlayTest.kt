package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.FakeFrameGrabber
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The desktop preview's overlay shares the box the camera frame is drawn in, so overlay
 * coordinates (a focus reticle, a detection box) land on the frame a capture writes, not on the
 * letterbox bars around it.
 */
class DesktopPreviewOverlayTest {

    @Test
    fun theOverlay_coversExactlyTheDrawnFrame() = runBlocking {
        // A 4:3 camera in a 2:1 preview: the frame is pillarboxed.
        val controller = CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = FakeFrameGrabber(width = 64, height = 48).apply { openGate() },
            sessionDispatcher = Dispatchers.IO,
            recorderFactory = { _, _ -> error("The preview test does not record") },
        )
        val overlayBounds = AtomicReference<Rect?>(null)
        val scene = ImageComposeScene(width = 200, height = 100, density = Density(1f)) {
            CameraPreviewView(controller, Modifier.fillMaxSize(), DeviceOrientation.PORTRAIT) {
                Box(Modifier.matchParentSize().onGloballyPositioned { overlayBounds.set(it.boundsInRoot()) })
            }
        }
        try {
            controller.startSession()

            // Render until a camera frame has been drawn, then once more so the layout has settled.
            val drawn = withTimeoutOrNull(10_000) {
                while (drawnFrameBounds(scene) == null) delay(10)
                drawnFrameBounds(scene)
            }

            val frame = assertNotNull(drawn, "no camera frame was drawn")
            val overlay = assertNotNull(overlayBounds.get(), "the overlay was never laid out")
            assertEquals(frame, overlay.toIntRect(), "overlay bounds versus the drawn frame")
        } finally {
            scene.close()
            controller.closeAndJoin()
        }
    }

    // The fake camera's frames are opaque black and the scene's background is transparent, so the
    // opaque pixels are exactly the drawn frame.
    private fun drawnFrameBounds(scene: ImageComposeScene): IntRect? {
        val pixels = scene.render().toComposeImageBitmap().toPixelMap()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha < 1f) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        return if (left == Int.MAX_VALUE) null else IntRect(left, top, right, bottom)
    }

    private fun Rect.toIntRect() = IntRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
}
