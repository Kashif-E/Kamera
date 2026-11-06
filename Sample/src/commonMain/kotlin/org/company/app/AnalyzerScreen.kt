package org.company.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kashif.analyzerplugin.AnalyzerPlugin
import com.kashif.analyzerplugin.EncodedFrame
import com.kashif.analyzerplugin.rememberAnalyzerPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

/** The latest analyzed frame's summary. It alone reads the analyzer, so only it redraws per frame. */
@Composable
internal fun AnalyzerLabel(analyzer: AnalyzerFeature, modifier: Modifier = Modifier) {
    LaunchedEffect(analyzer) { analyzer.summarize() }
    val summary by analyzer.summary.collectAsState()
    val latest = summary ?: return
    Text(
        text = "Analyzer: frame ${latest.frames}, ${latest.width}×${latest.height}, ${latest.jpegBytes / 1024} KB JPEG",
        color = TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
