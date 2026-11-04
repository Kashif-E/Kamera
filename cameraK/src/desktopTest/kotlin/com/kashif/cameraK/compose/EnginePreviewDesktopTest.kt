@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewFrame
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The desktop actual of EnginePreview draws the engine's latest frame letterboxed to the frame's
 * own aspect ratio, draws nothing while the source has no frame, and collects only the source it
 * was last composed with.
 */
class EnginePreviewDesktopTest {
    private class Source : PlatformPreviewSource {
        override val frames = MutableStateFlow<PreviewFrame?>(null)
    }

    private class Rig(first: Source) {
        var source by mutableStateOf(first)
        private val scene = ImageComposeScene(width = SIZE, height = SIZE) {
            EnginePreview(source, DeviceOrientation.PORTRAIT, Modifier.fillMaxSize(), overlay = null)
        }

        fun frame(): PixelMap {
            Snapshot.sendApplyNotifications()
            return scene.render().toComposeImageBitmap().toPixelMap()
        }

        /** Renders until [condition] holds: the collector converts frames off the UI thread. */
        fun await(what: String, condition: (PixelMap) -> Boolean): PixelMap {
            val deadline = System.nanoTime() + AWAIT_NANOS
            while (true) {
                val pixels = frame()
                if (condition(pixels)) return pixels
                if (System.nanoTime() > deadline) fail("$what within ${AWAIT_NANOS / 1_000_000} ms")
                Thread.sleep(5)
            }
        }

        fun close() = scene.close()
    }

    @Test
    fun nullFrameDrawsNothingAndAFrameIsLetterboxed() {
        val source = Source()
        val rig = Rig(source)
        try {
            assertTrue(rig.frame().isBlank(), "drawn before the first frame")

            // A 2:1 frame in a square preview: bars of a quarter of the height above and below.
            source.frames.value = solidFrame(width = 200, height = 100, rgb = ORANGE)
            val pixels = rig.await("the frame was not drawn") { it.rgbAt(SIZE / 2, SIZE / 2) == ORANGE }
            for (y in listOf(0, SIZE / 4 - 2, SIZE * 3 / 4 + 1, SIZE - 1)) {
                assertEquals(0, pixels.alphaAt(SIZE / 2, y), "row $y is in the letterbox bar")
            }
            for (y in listOf(SIZE / 4 + 1, SIZE / 2, SIZE * 3 / 4 - 2)) {
                assertEquals(ORANGE, pixels.rgbAt(0, y), "row $y, left edge")
                assertEquals(ORANGE, pixels.rgbAt(SIZE - 1, y), "row $y, right edge")
            }

            source.frames.value = null
            val blank = rig.await("a null frame still shows the last one") { it.alphaAt(SIZE / 2, SIZE / 2) == 0 }
            assertTrue(blank.isBlank(), "drawn after the source went back to null")
        } finally {
            rig.close()
        }
    }

    /** desktop-preview-collector-leak: a new source cancels the collector of the old one. */
    @Test
    fun aNewSourceStopsCollectingTheOldOne() {
        val first = Source()
        val second = Source()
        val rig = Rig(first)
        try {
            first.frames.value = solidFrame(width = 40, height = 40, rgb = ORANGE)
            rig.await("the first source was not drawn") { it.rgbAt(SIZE / 2, SIZE / 2) == ORANGE }
            assertEquals(1, first.frames.subscriptionCount.value)

            rig.source = second
            second.frames.value = solidFrame(width = 40, height = 40, rgb = TEAL)
            rig.await("the second source was not drawn") { it.rgbAt(SIZE / 2, SIZE / 2) == TEAL }
            awaitTrue("the old source is still collected") { first.frames.subscriptionCount.value == 0 }
            assertEquals(1, second.frames.subscriptionCount.value)
        } finally {
            rig.close()
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + AWAIT_NANOS
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("$what after ${AWAIT_NANOS / 1_000_000} ms")
            Thread.sleep(5)
        }
    }

    private fun solidFrame(width: Int, height: Int, rgb: Int): PreviewFrame {
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        return PreviewFrame(image, timestampNanos = 0)
    }

    private fun PixelMap.rgbAt(x: Int, y: Int): Int = this[x, y].toArgb() and 0xFFFFFF

    private fun PixelMap.alphaAt(x: Int, y: Int): Int = this[x, y].toArgb() ushr 24

    private fun PixelMap.isBlank(): Boolean = (0 until SIZE).all { y -> (0 until SIZE).all { x -> alphaAt(x, y) == 0 } }

    private companion object {
        const val SIZE = 80
        const val ORANGE = 0xFF8040
        const val TEAL = 0x208080
        const val AWAIT_NANOS = 5_000_000_000L
    }
}
