package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kashif.analyzerPlugin.AnalyzerPlugin
import com.kashif.analyzerPlugin.rememberAnalyzerPlugin
import kotlinx.coroutines.CoroutineScope

/** The Sample's frame analyzer, feeding the TFLite object detector where the platform has one. */
internal class AnalyzerFeature(val plugin: AnalyzerPlugin)

@Composable
internal fun rememberAnalyzerFeature(): AnalyzerFeature {
    val plugin = rememberAnalyzerPlugin()
    return remember(plugin) { AnalyzerFeature(plugin) }
}

/** The most recent analyzed frame. */
@Composable
internal fun rememberLatestFrame(analyzer: AnalyzerFeature): ByteArray? {
    var latestFrame by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(analyzer) {
        analyzer.plugin.getAnalyzerFlow().collect { frame ->
            latestFrame = frame
        }
    }
    return latestFrame
}

@Composable
internal fun AnalyzerEffect(analyzer: AnalyzerFeature, isDetectionEnabled: Boolean) {
    LaunchedEffect(isDetectionEnabled) {
        try {
            if (isDetectionEnabled) {
                analyzer.plugin.startAnalyzer()
            } else {
                analyzer.plugin.stopAnalyzer()
            }
        } catch (_: Exception) {
        }
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
