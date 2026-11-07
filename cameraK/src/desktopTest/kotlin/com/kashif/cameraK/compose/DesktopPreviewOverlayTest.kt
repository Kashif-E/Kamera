@file:OptIn(ExperimentalCoroutinesApi::class)

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
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The desktop preview's overlay shares the box the camera frame is drawn in, so overlay
 * coordinates (a focus reticle, a detection box) land on the frame a capture writes, not on the
 * letterbox bars around it. Over camerak-testing's fake engine, whose preview publishes one fixed,
 * opaque 640 x 480 frame.
 */
class DesktopPreviewOverlayTest {
    @Test
    fun theOverlay_coversExactlyTheDrawnFrame() = runTest {
        // A 4:3 camera in a 2:1 preview: the frame is pillarboxed.
        val holder = fakeCameraKStateHolder()
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        val overlayBounds = AtomicReference<Rect?>(null)
        val scene = ImageComposeScene(
            width = 200,
            height = 100,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            CameraPreview(holder, Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().onGloballyPositioned { overlayBounds.set(it.boundsInRoot()) })
            }
        }
        try {
            // Render until the frame has been drawn, then once more so the layout has settled.
            var drawn: IntRect? = null
            repeat(RENDERS) {
                if (drawn == null) {
                    runCurrent()
                    drawn = drawnFrameBounds(scene)
                }
            }
            runCurrent()
            drawn = drawnFrameBounds(scene)

            val frame = assertNotNull(drawn, "no camera frame was drawn")
            val overlay = assertNotNull(overlayBounds.get(), "the overlay was never laid out")
            assertEquals(frame, overlay.toIntRect(), "overlay bounds versus the drawn frame")
        } finally {
            scene.close()
            holder.close()
        }
    }

    @Test
    fun anOverlaysFillMaxSize_measuresToTheContentRectangle() = runTest {
        // The fake's 640 x 480 frame in a 200 x 100 preview: a 133 x 100 content rectangle, centred
        // as Alignment.Center places it, with the pillarbox bars outside it.
        val holder = fakeCameraKStateHolder()
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        val overlayBounds = AtomicReference<Rect?>(null)
        val contentBounds = AtomicReference<Rect?>(null)
        val scene = ImageComposeScene(
            width = 200,
            height = 100,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            CameraPreview(holder, Modifier.fillMaxSize()) {
                contentBounds.set(this.contentBounds)
                Box(Modifier.fillMaxSize().onGloballyPositioned { overlayBounds.set(it.boundsInRoot()) })
            }
        }
        try {
            // The preview collects the fake's frame on Dispatchers.Default, outside the test's virtual
            // time; until its size reaches the layout the content rectangle is the whole scene.
            val deadline = System.nanoTime() + AWAIT_NANOS
            while ((contentBounds.get()?.width ?: 200f) >= 200f) {
                if (System.nanoTime() > deadline) {
                    fail("the fake\'s frame did not reach the preview in ${AWAIT_NANOS / 1_000_000} ms")
                }
                runCurrent()
                scene.render()
                Thread.sleep(5)
            }
            repeat(RENDERS) {
                runCurrent()
                scene.render()
            }

            val overlay = assertNotNull(overlayBounds.get(), "the overlay was never laid out")
            assertEquals(IntRect(34, 0, 167, 100), overlay.toIntRect(), "the overlay's box in the preview")
            assertEquals(Rect(0f, 0f, 133f, 100f), contentBounds.get(), "contentBounds, in the overlay's coordinates")
        } finally {
            scene.close()
            holder.close()
        }
    }

    // The fake's frame is opaque and the scene's background is transparent, so the opaque pixels
    // are exactly the drawn frame.
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

    private companion object {
        const val RENDERS = 20
        const val AWAIT_NANOS = 5_000_000_000L
    }
}
