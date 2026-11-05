@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.pixelBuffer
import com.kashif.cameraK.testing.TestFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Vision detections through the plugin, on frames whose pixel buffer is null: the plugin
 * decodes from the luma `copyLuma` writes, never from the frame's own buffer (G5). Each frame holds
 * one QR code for "HELLO", and the expected box is its dark pixels' bounds in frame pixels.
 * Vision locates a code to within a module, so a box edge may differ by one module ([MODULE] px);
 * a wrong orientation, region normalisation or y flip moves it by tens of pixels.
 *
 * The decoder pins Vision's revision 2: on the simulator, revisions 3 and 4, the default on current
 * iOS, find no code and report no error. The default revision on a device is B1's QM run.
 */
class VisionDetectionTest {
    @Test
    fun aQrCodeInAFrameWithoutAPixelBufferDecodesThroughVision() = runTest {
        val scene = qrScene()

        val barcode = scan(QRScannerConfig(), scene.frame(rotationDegrees = 0))

        assertEquals("HELLO", barcode.rawValue)
        assertEquals(BarcodeFormat.QR_CODE, barcode.format)
        assertBoxNear(scene.codeBounds, barcode.boundingBox)
    }

    @Test
    fun aRotatedFrameDecodesWithItsBoxInFramePixels() = runTest {
        val scene = qrScene()

        val barcode = scan(QRScannerConfig(), scene.frame(rotationDegrees = 90))

        assertEquals("HELLO", barcode.rawValue)
        assertBoxNear(scene.codeBounds, barcode.boundingBox)
    }

    @Test
    fun aRegionDecodesWithItsBoxInFramePixels() = runTest {
        val scene = qrScene()
        val region = FrameRect(100, 20, 300, 230)

        val upright = scan(QRScannerConfig { this.region = region }, scene.frame(rotationDegrees = 0))
        val rotated = scan(QRScannerConfig { this.region = region }, scene.frame(rotationDegrees = 270))

        assertBoxNear(scene.codeBounds, upright.boundingBox, "upright")
        assertBoxNear(scene.codeBounds, rotated.boundingBox, "rotated 270")
    }

    @Test
    fun aRegionWithoutTheCodeFindsNothing() = runTest {
        val scene = qrScene()
        val plugin = QRScannerPlugin(QRScannerConfig { region = FrameRect(0, 0, 120, 240) }, REVISION_2)
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(scene.frame(rotationDegrees = 0))

        assertTrue(results.isEmpty(), "codes found outside the region: $results")
    }

    private fun TestScope.scan(config: QRScannerConfig, frame: TestFrame): Barcode {
        assertNull(frame.pixelBuffer, "a test frame's pixel buffer")
        val plugin = QRScannerPlugin(config, REVISION_2)
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(frame)

        return results.singleOrNull() ?: error("expected one code, got $results")
    }

    private fun assertBoxNear(expected: FrameRect, actual: FrameRect?, label: String = "box") {
        val box = assertNotNull(actual, "$label: no bounding box")
        val near = expected.edges().zip(box.edges()).all { (e, a) -> abs(e - a) <= MODULE }
        assertTrue(near, "$label ${box.edges()}, not within $MODULE px of ${expected.edges()}")
    }

    /** A [WIDTH] x [HEIGHT] white luma frame holding a QR code for "HELLO" at [MODULE] px a module. */
    private class QrScene(val luma: ByteArray, val codeBounds: FrameRect) {
        fun frame(rotationDegrees: Int) = TestFrame.luma(WIDTH, HEIGHT, luma, rotationDegrees = rotationDegrees)
    }

    private fun qrScene(): QrScene {
        val luma = ByteArray(WIDTH * HEIGHT) { WHITE }
        for ((row, line) in HELLO.withIndex()) {
            for ((column, module) in line.withIndex()) {
                if (module != '#') continue
                for (y in 0 until MODULE) {
                    val start = (CODE_TOP + row * MODULE + y) * WIDTH + CODE_LEFT + column * MODULE
                    luma.fill(0, start, start + MODULE)
                }
            }
        }
        return QrScene(luma, darkBounds(luma))
    }

    private fun darkBounds(luma: ByteArray): FrameRect {
        var left = WIDTH
        var top = HEIGHT
        var right = 0
        var bottom = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                if ((luma[y * WIDTH + x].toInt() and 0xFF) < 128) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
        }
        check(right > left) { "the scene holds no dark pixel" }
        return FrameRect(left, top, right, bottom)
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val MODULE = 6
        const val CODE_LEFT = 150
        const val CODE_TOP = 40
        const val WHITE: Byte = -1

        val REVISION_2 = BarcodeDecoderFactory { formats -> VisionBarcodeDecoder(formats, pinnedRevision = 2u) }

        // "HELLO" as a version 1 QR code at level M, one character a module ('#' dark), from
        // ZXing's encoder; the white around it is the quiet zone.
        val HELLO = listOf(
            "#######...###.#######",
            "#.....#....#..#.....#",
            "#.###.#.#.#.#.#.###.#",
            "#.###.#.#..##.#.###.#",
            "#.###.#.#####.#.###.#",
            "#.....#.##..#.#.....#",
            "#######.#.#.#.#######",
            "........#..##........",
            "#.#####.....#.#####..",
            "....#..###..#..#.####",
            "##..####...#.#..##..#",
            "..##.....#.....##....",
            "....####..##.#..#.#..",
            "........#.#####..#.##",
            "#######.....#.##..#.#",
            "#.....#.#######..#..#",
            "#.###.#.#.#.#..#..#..",
            "#.###.#.#...#..#..#..",
            "#.###.#.#..#.#..#.#..",
            "#.....#...#....##.#.#",
            "#######.#.##.#..#.#..",
        )
    }
}
