package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kashif.analyzerPlugin.AnalyzerConfig
import com.kashif.analyzerPlugin.AnalyzerPlugin
import com.kashif.analyzerPlugin.rememberAnalyzerPlugin
import kotlinx.coroutines.CoroutineScope

/** The Sample's frame analyzer, feeding the TFLite object detector where the platform has one. */
internal class AnalyzerFeature(val plugin: AnalyzerPlugin)

// Object detection wants a steadier stream than the analyzer's default of one frame a second.
private val SampleAnalyzerConfig = AnalyzerConfig { maxFps = 5f }

@Composable
internal fun rememberAnalyzerFeature(): AnalyzerFeature {
    val plugin = rememberAnalyzerPlugin(SampleAnalyzerConfig)
    return remember(plugin) { AnalyzerFeature(plugin) }
}

/** The most recent analyzed frame's JPEG. Collecting it is what makes the analyzer encode. */
@Composable
internal fun rememberLatestFrame(analyzer: AnalyzerFeature): ByteArray? {
    var latestFrame by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(analyzer) {
        analyzer.plugin.results.collect { frame ->
            latestFrame = frame.bytes
        }
    }
    return latestFrame
}

@Composable
internal fun AnalyzerEffect(analyzer: AnalyzerFeature, isDetectionEnabled: Boolean) {
    LaunchedEffect(analyzer, isDetectionEnabled) {
        if (isDetectionEnabled) analyzer.plugin.resume() else analyzer.plugin.pause()
    }
}

@Composable
internal fun ObjectDetectionEffect(latestFrame: ByteArray?, scope: CoroutineScope) {
    val runTFliteModel = remember { getTFliteRunner() }

    if (runTFliteModel != null) {
        LaunchedEffect(latestFrame) {
            latestFrame?.runTFliteModel(scope)
        }
    }
}
