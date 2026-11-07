@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The release gate's benchmarks (benchmark/, a com.android.test module run only on a device) drive the
 * Sample by UiAutomator selectors in SampleControls.kt. This reads those selectors and finds each in
 * the redesigned camera screen, on camerak-testing's fake: a `By.desc` value as a content description,
 * a `By.text` value as a text. The screen is composed in the states the benchmarks reach: the photo
 * mode at each of the Sample's ratios, the video mode, a recording, and the open settings.
 */
class BenchmarkSelectorsTest {
    private val sources = File(
        System.getProperty(SOURCES_PROPERTY) ?: fail("$SOURCES_PROPERTY is not set; Sample/build.gradle.kts sets it"),
    )
    private val controls = File(sources, "SampleControls.kt").readText()

    @Test
    fun everyControlIsFoundByASelector() {
        assertTrue(
            "device.click(" !in controls,
            "SampleControls.kt clicks a screen position; a control moved by the redesign gets a focus tap instead",
        )
    }

    @Test
    fun theShutterIsFoundByItsDescription() {
        assertTrue("Take photo" in selectors().getValue("desc"), "no By.desc(\"Take photo\") for the shutter")
    }

    @Test
    fun theRatioChipCyclesAsTheBenchmarksExpect() {
        val chip = generateSequence(sampleAspectRatios.first()) { it.nextInSample() }
            .take(sampleAspectRatios.size)
            .map { it.label() }
            .toList()
        assertEquals(chip, aspectRatios(), "ASPECT_RATIOS is not the order the Sample's ratio chip cycles in")

        val asked = sources.listFiles().orEmpty().filter { it.extension == "kt" }.flatMap { file ->
            SELECT_RATIO.findAll(file.readText()).flatMap {
                LITERAL.findAll(it.groupValues[1])
            }.map { it.groupValues[1] }
        }
        assertTrue(asked.isNotEmpty(), "no selectAspectRatio call found in ${sources.name}")
        assertEquals(emptyList(), asked.filterNot { it in chip }, "a benchmark selects a ratio the chip never shows")
    }

    @Test
    fun everySelectorMatchesTheRedesignedScreen() = runTest {
        val holder = fakeCameraKStateHolder(fake = FakeCameraEngine())
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        var settings by mutableStateOf(CameraSettings())
        val descriptions = mutableSetOf<String>()
        val texts = mutableSetOf<String>()
        var qrSwitchToggleable = false

        SampleScene(this) {
            CameraScreen(
                holder = holder,
                qrScanner = rememberQrScannerFeature(),
                ocr = rememberOcrFeature(),
                analyzer = rememberAnalyzerFeature(),
                snackbarHostState = remember { SnackbarHostState() },
                qrCodes = emptyList(),
                recognizedText = null,
                settings = settings,
                onSettingsChange = { settings = it },
                options = SampleOptions(
                    saveToGallery = false,
                    onSaveToGalleryChange = {},
                    ocrAccuracy = OcrAccuracy.Fast,
                    onOcrAccuracyChange = {},
                ),
            )
        }.use { scene ->
            fun collect() = scene.nodes().forEach { node ->
                descriptions += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                texts += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
            for (ratio in sampleAspectRatios) {
                settings = settings.copy(aspectRatio = ratio)
                scene.settle()
                collect()
            }
            settings = settings.copy(aspectRatio = AspectRatio.RATIO_4_3)
            scene.settle()

            scene.tap(scene.node("VIDEO"))
            collect()
            scene.tap(scene.node("Start recording"))
            collect()
            scene.tap(scene.node("Stop recording"))
            scene.tap(scene.node("Settings"))
            collect()
            qrSwitchToggleable = scene.nodes().any { it.reads("QR Scanner") && it.isToggleable() }
        }
        holder.close()

        val found = selectors()
        val missing = found.getValue("desc").filterNot { it in descriptions }.map { "By.desc(\"$it\")" } +
            found.getValue("text").filterNot { it in texts }.map { "By.text(\"$it\")" }
        assertEquals(emptyList(), missing, "selectors in SampleControls.kt that match nothing on the redesigned screen")
        assertTrue(qrSwitchToggleable, "the QR Scanner setting is not a toggleable row, which toggleQrScanner needs")
    }

    // Every By.desc and By.text argument in SampleControls.kt, by kind, through its string constants;
    // an argument that is neither a literal nor a constant is one of ASPECT_RATIOS, in a function that
    // reads that list.
    private fun selectors(): Map<String, Set<String>> {
        val constants = CONSTANT.findAll(controls).associate { it.groupValues[1] to it.groupValues[2] }
        val ratios = aspectRatios()
        val found = mapOf("desc" to mutableSetOf<String>(), "text" to mutableSetOf())
        for (match in SELECTOR.findAll(controls)) {
            val argument = match.groupValues[2].trim()
            val values = LITERAL.matchEntire(argument)?.let { listOf(it.groupValues[1]) }
                ?: constants[argument]?.let(::listOf)
                ?: ratios.takeIf { "ASPECT_RATIOS" in enclosingFunction(match.range.first) }
                ?: fail("cannot resolve the selector argument `$argument` in SampleControls.kt")
            found.getValue(match.groupValues[1]) += values
        }
        return found
    }

    private fun aspectRatios(): List<String> {
        val list = ASPECT_RATIOS.find(controls) ?: fail("SampleControls.kt has no ASPECT_RATIOS list")
        return LITERAL.findAll(list.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    private fun enclosingFunction(at: Int): String {
        val start = DECLARATION.findAll(controls).lastOrNull { it.range.first < at }?.range?.first ?: 0
        val end = DECLARATION.findAll(controls).firstOrNull { it.range.first > at }?.range?.first ?: controls.length
        return controls.substring(start, end)
    }

    private fun SemanticsNode.reads(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }

    private fun SemanticsNode.isToggleable(): Boolean = config.getOrNull(SemanticsProperties.ToggleableState) != null

    private companion object {
        const val SOURCES_PROPERTY = "camerak.benchmarkSources"
        val CONSTANT = Regex("""const val (\w+)\s*=\s*"([^"]*)"""")
        val LITERAL = Regex(""""([^"]*)"""")
        val SELECTOR = Regex("""By\.(desc|text)\(((?:[^()]|\([^()]*\))*)\)""")
        val ASPECT_RATIOS = Regex("""val ASPECT_RATIOS\s*=\s*listOf\(([^)]*)\)""")
        val SELECT_RATIO = Regex("""selectAspectRatio\(((?:[^()]|\([^()]*\))*)\)""")
        val DECLARATION = Regex("""(?m)^(?:internal |private )?(?:fun|val|const val) """)
    }
}
