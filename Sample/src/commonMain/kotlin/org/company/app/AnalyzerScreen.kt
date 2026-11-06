package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.analyzerplugin.AnalyzerPlugin
import com.kashif.analyzerplugin.EncodedFrame
import com.kashif.analyzerplugin.rememberAnalyzerPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The Sample's frame analyzer: analysis frames as JPEGs, at the analyzer's default rate. */
internal class AnalyzerFeature(val plugin: AnalyzerPlugin, private val frames: Flow<EncodedFrame> = plugin.results) {
    val summary: StateFlow<AnalyzedFrameSummary?> get() = TODO("x-sample red stub")

    suspend fun summarize(): Unit = TODO("x-sample red stub")
}

internal data class AnalyzedFrameSummary(val frames: Int, val width: Int, val height: Int, val jpegBytes: Int)

@Composable
internal fun rememberAnalyzerFeature(): AnalyzerFeature {
    val plugin = rememberAnalyzerPlugin()
    return remember(plugin) { AnalyzerFeature(plugin) }
}
