package org.company.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kashif.analyzerplugin.AnalyzerPlugin
import com.kashif.analyzerplugin.EncodedFrame
import com.kashif.analyzerplugin.rememberAnalyzerPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.Sheet
import org.company.app.designsystem.SheetHeader
import org.company.app.designsystem.SheetHeaderEmphasis
import org.company.app.designsystem.SheetMeta

/**
 * The Sample's frame analyzer: analysis frames as JPEGs, at the analyzer's default rate. It keeps a
 * summary of the latest one and drops the JPEG, so nothing on screen holds or redraws for each frame's
 * bytes; an app would hand them to its model here instead.
 */
internal class AnalyzerFeature(val plugin: AnalyzerPlugin, private val frames: Flow<EncodedFrame> = plugin.results) {
    private val _summary = MutableStateFlow<AnalyzedFrameSummary?>(null)

    /** The latest frame's summary, or null before the first. */
    val summary: StateFlow<AnalyzedFrameSummary?> = _summary.asStateFlow()

    /** Summarizes each frame until cancelled. Collecting the frames is what makes the analyzer encode. */
    suspend fun summarize() {
        frames.collect { frame ->
            _summary.update { last ->
                AnalyzedFrameSummary(
                    frames = (last?.frames ?: 0) + 1,
                    width = frame.width,
                    height = frame.height,
                    jpegBytes = frame.bytes.size,
                )
            }
        }
    }
}

/** What the Sample shows of an analyzed frame: how many so far, the latest one's size and JPEG length. */
internal data class AnalyzedFrameSummary(val frames: Int, val width: Int, val height: Int, val jpegBytes: Int)

@Composable
internal fun rememberAnalyzerFeature(): AnalyzerFeature {
    val plugin = rememberAnalyzerPlugin()
    return remember(plugin) { AnalyzerFeature(plugin) }
}

/**
 * The Analyze mode's sheet: the latest analyzed frame's summary. The analyzer encodes only while this
 * is shown, and only this reads it, so only this redraws per frame.
 */
@Composable
internal fun AnalyzerSheet(analyzer: AnalyzerFeature, modifier: Modifier = Modifier) {
    LaunchedEffect(analyzer) { analyzer.summarize() }
    val summary by analyzer.summary.collectAsState()
    Sheet(modifier = modifier) {
        val latest = summary
        SheetHeader(title = "Frame analyzer", emphasis = SheetHeaderEmphasis.Eyebrow) {
            if (latest != null) SheetMeta(if (latest.frames == 1) "1 frame" else "${latest.frames} frames")
        }
        Text(
            text = latest?.let { "${it.width}×${it.height} · ${it.jpegBytes / 1024} KB JPEG" }
                ?: "Waiting for the first frame",
            style = SampleTheme.typography.value,
            color = if (latest == null) SampleTheme.colors.inkSecondary else SampleTheme.colors.ink,
        )
        Text(
            text = "Each analysis frame arrives as a JPEG; an app hands it to its model here.",
            style = SampleTheme.typography.caption,
            color = SampleTheme.colors.inkSecondary,
        )
    }
}
