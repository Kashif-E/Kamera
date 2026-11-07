package org.company.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kashif.ocrplugin.OcrConfig
import com.kashif.ocrplugin.OcrLevel
import com.kashif.ocrplugin.OcrPlugin
import com.kashif.ocrplugin.rememberOcrPlugin
import org.company.app.designsystem.PrimaryButton
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.SegmentedControl
import org.company.app.designsystem.Sheet
import org.company.app.designsystem.SheetHeader
import org.company.app.designsystem.SheetMeta

/**
 * How hard live recognition works: the Text mode's Fast / Accurate switch. Android's ML Kit has one
 * model and reads both the same.
 */
internal enum class OcrAccuracy(val label: String) {
    Fast("Fast"),
    Accurate("Accurate"),
}

/** The Sample's text recognition: the latest recognized text, in the Text mode's sheet. */
internal class OcrFeature(val plugin: OcrPlugin)

/** The recognizer at [accuracy]; a new accuracy is a new plugin, which the holder swaps for the old one. */
@Composable
internal fun rememberOcrFeature(accuracy: OcrAccuracy = OcrAccuracy.Fast): OcrFeature {
    val config = remember(accuracy) {
        OcrConfig { level = if (accuracy == OcrAccuracy.Accurate) OcrLevel.ACCURATE else OcrLevel.FAST }
    }
    val plugin = rememberOcrPlugin(config)
    return remember(plugin) { OcrFeature(plugin) }
}

@Composable
internal fun rememberRecognizedText(ocr: OcrFeature): String? {
    var recognizedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ocr) {
        ocr.plugin.results.collect { recognizedText = it.text }
    }
    return recognizedText
}

@Composable
internal fun OcrRecognitionEffect(ocr: OcrFeature, isOCREnabled: Boolean) {
    LaunchedEffect(ocr, isOCREnabled) {
        if (isOCREnabled) ocr.plugin.resume() else ocr.plugin.pause()
    }
}

/** The Text mode's Fast / Accurate switch, for its top bar. */
@Composable
internal fun OcrAccuracyToggle(accuracy: OcrAccuracy, onChange: (OcrAccuracy) -> Unit, modifier: Modifier = Modifier) {
    SegmentedControl(
        options = OcrAccuracy.entries,
        selected = accuracy,
        onSelect = onChange,
        label = { it.label },
        modifier = modifier,
    )
}

/** The Text mode's sheet: the recognized lines, all of them on a tap, and Copy all. */
@Composable
internal fun TextResultSheet(text: String?, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Sheet(modifier = modifier) {
        val lines = text?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) {
            SheetHeader(title = "No text yet")
            Text(
                text = "Hold printed text in view; what is read shows here.",
                style = SampleTheme.typography.body,
                color = SampleTheme.colors.inkSecondary,
            )
            return@Sheet
        }
        SheetHeader(title = "Text Detected") {
            SheetMeta(if (lines.size == 1) "1 line" else "${lines.size} lines")
        }
        Text(
            text = lines.joinToString("\n"),
            style = SampleTheme.typography.body,
            color = SampleTheme.colors.ink,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        )
        PrimaryButton(
            text = "Copy all",
            onClick = { onCopy(lines.joinToString("\n")) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val COLLAPSED_LINES = 6
