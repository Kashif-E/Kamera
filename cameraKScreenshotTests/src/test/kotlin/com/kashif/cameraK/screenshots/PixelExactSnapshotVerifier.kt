package com.kashif.cameraK.screenshots

import app.cash.paparazzi.HtmlReportWriter
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import app.cash.paparazzi.SnapshotVerifier
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * Paparazzi 2.0.0-alpha02 always compares with its off-by-two differ: a pixel whose channels each
 * moved by 2 or less counts as identical, so even `maxPercentDifference = 0.0` passes a colour shift
 * of one level. This handler runs Paparazzi's own verifier first, which writes the delta image for a
 * difference it sees, and then requires every pixel to equal the golden.
 */
class PixelExactSnapshotVerifier : SnapshotHandler {
    private val paparazziVerifier = SnapshotVerifier(maxPercentDifference = 0.0)
    private val imagesDirectory = File(System.getProperty("paparazzi.snapshot.dir"), "images")
    private val failureDirectory = File(System.getProperty("paparazzi.build.dir"), "paparazzi/failures")

    override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
        check(frameCount == 1) { "Pixel-exact verification covers single-frame snapshots, not $frameCount frames" }
        val frames = paparazziVerifier.newFrameHandler(snapshot, frameCount, fps)
        val fileName = goldenFileName(snapshot)
        return object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) {
                frames.handle(image)
                assertPixelExact(File(imagesDirectory, fileName), image, fileName)
            }

            override fun close() = frames.close()
        }
    }

    override fun close() = paparazziVerifier.close()

    private fun assertPixelExact(golden: File, actual: BufferedImage, fileName: String) {
        val expected = checkNotNull(ImageIO.read(golden)) { "Could not decode golden $golden" }
        check(expected.width == actual.width && expected.height == actual.height) {
            "$fileName: golden is ${expected.width}x${expected.height}, render is ${actual.width}x${actual.height}"
        }
        var differing = 0
        var maxChannelDelta = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val a = expected.getRGB(x, y)
                val b = actual.getRGB(x, y)
                if (a == b) continue
                differing++
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    maxChannelDelta = max(maxChannelDelta, abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)))
                }
            }
        }
        if (differing == 0) return
        failureDirectory.mkdirs()
        val written = File(failureDirectory, "exact-actual-$fileName")
        ImageIO.write(actual, "png", written)
        throw AssertionError(
            "$fileName: $differing of ${actual.width * actual.height} pixels differ from the golden " +
                "(largest channel difference $maxChannelDelta). Render written to $written",
        )
    }

    // Paparazzi's own Snapshot.toFileName is internal. A drift from it fails loudly: the golden
    // under this name would not exist.
    private fun goldenFileName(snapshot: Snapshot): String {
        val test = snapshot.testName
        val label = snapshot.name?.let { "_" + it.lowercase(Locale.US).replace(Regex("\\s"), "_") } ?: ""
        return "${test.packageName}_${test.className}_${test.methodName.replace(Regex("\\s"), "_")}$label.png"
    }

    companion object {
        /** The verifier when Paparazzi is verifying, Paparazzi's own recorder otherwise. */
        fun forCurrentMode(): SnapshotHandler = if (System.getProperty(
                "paparazzi.test.verify",
            ).toBoolean()
        ) {
            PixelExactSnapshotVerifier()
        } else {
            HtmlReportWriter()
        }
    }
}
