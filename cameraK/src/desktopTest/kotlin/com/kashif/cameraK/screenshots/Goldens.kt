package com.kashif.cameraK.screenshots

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.fail

/**
 * Renders a composable off-screen and compares it pixel for pixel with a committed golden PNG.
 *
 * The comparison is exact. Text uses the bundled DroidSans instead of the host's system font, so
 * an OS font update cannot change the render. Goldens are recorded on macOS (the CI host); Skia
 * rasterises glyphs through the platform font backend, so a golden recorded on another OS can
 * differ in anti-aliasing.
 */
internal object Goldens {
    private val record = System.getProperty("camerak.screenshots.record").toBoolean()
    private val goldenDir = File(requiredProperty("camerak.screenshots.goldenDir"))
    private val diffDir = File(requiredProperty("camerak.screenshots.diffDir"))

    private val testFontFamily: FontFamily by lazy {
        val bytes =
            checkNotNull(Goldens::class.java.getResourceAsStream("/fonts/DroidSans.ttf")) {
                "fonts/DroidSans.ttf missing from desktopTest resources"
            }.use { it.readBytes() }
        FontFamily(Font(identity = "DroidSans", data = bytes))
    }

    fun assertMatches(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val png = render(width, height, content)
        val golden = File(goldenDir, "$name.png")

        if (record) {
            golden.parentFile.mkdirs()
            golden.writeBytes(png)
            println("Recorded golden ${golden.path}")
            return
        }

        if (!golden.exists()) {
            writeFailure(name, png, diff = null)
            fail("No golden for '$name' at ${golden.path}. Record it with scripts/verify.sh --record-screenshots.")
        }

        val actual = decode(png)
        val expected = ImageIO.read(golden) ?: fail("Golden ${golden.path} is not a readable PNG")
        if (actual.width != expected.width || actual.height != expected.height) {
            writeFailure(name, png, diff = null)
            fail(
                "'$name' is ${actual.width}x${actual.height}, golden is ${expected.width}x${expected.height}. " +
                    "Actual written to $diffDir",
            )
        }

        val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        var differing = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val same = actual.getRGB(x, y) == expected.getRGB(x, y)
                if (!same) differing++
                diff.setRGB(x, y, if (same) expected.getRGB(x, y) and 0x33FFFFFF else 0xFFFF0000.toInt())
            }
        }
        if (differing > 0) {
            writeFailure(name, png, diff)
            fail("'$name' differs from its golden in $differing pixel(s). Actual and diff written to $diffDir")
        }
    }

    private fun render(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = testFontFamily),
                ) {
                    content()
                }
            }
        return try {
            // nanoTime 0 pins every animation (the loading spinner) to its first frame.
            val image = scene.render(nanoTime = 0)
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Skia could not encode the render as PNG" }.bytes
        } finally {
            scene.close()
        }
    }

    private fun decode(png: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(png)) ?: fail("Render is not a readable PNG")

    private fun writeFailure(name: String, png: ByteArray, diff: BufferedImage?) {
        diffDir.mkdirs()
        File(diffDir, "$name.actual.png").writeBytes(png)
        diff?.let { ImageIO.write(it, "png", File(diffDir, "$name.diff.png")) }
    }

    private fun requiredProperty(key: String): String = checkNotNull(System.getProperty(key)) {
        "System property $key is not set; run these tests through Gradle (cameraK:desktopTest)"
    }
}
