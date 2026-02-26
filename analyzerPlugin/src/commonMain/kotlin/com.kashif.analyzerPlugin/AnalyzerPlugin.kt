package com.kashif.analyzerPlugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.plugins.CameraPlugin
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class AnalyzerPlugin(val coroutineScope: CoroutineScope) :
    CameraPlugin,
    CameraKPlugin {
    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null
    val analyzerFlow = MutableSharedFlow<ByteArray>()
    private var isAnalyzing = atomic(false)
    private var collectorJob: Job? = null

    override fun initialize(cameraController: CameraController) {
        println("Analyzer initialized (legacy API)")
        this.cameraController = cameraController
    }

    fun startAnalyzer() {
        isAnalyzing.value = true
        startAnalyzer(cameraController!!) {
            if (isAnalyzing.value) {
                coroutineScope.launch {
                    analyzerFlow.emit(it)
                }
            }
        }
    }

    fun stopAnalyzer() {
        isAnalyzing.value = false
    }

    /**
     * Attaches the plugin to the state holder (new API).
     * Automatically starts Analyzer when camera becomes ready.
     *
     * @param stateHolder The [CameraKStateHolder] to attach to.
     */
    override fun onAttach(stateHolder: CameraKStateHolder) {
        println("Analyzer attached (new API)")
        this.stateHolder = stateHolder

        collectorJob = stateHolder.pluginScope.launch {
            stateHolder.cameraState
                .filterIsInstance<CameraKState.Ready>()
                .collect { readyState ->
                    try {
                        this@AnalyzerPlugin.cameraController = readyState.controller
                        startAnalyzer()
                    } catch (e: Exception) {
                        println("Analyzer: Failed to start analyzer: ${e.message}")
                        e.printStackTrace()
                    }
                }
        }
    }

    /**
     * Detaches the plugin from the state holder and cleans up resources.
     */
    override fun onDetach() {
        println("com.kashif.analyzerPlugin.AnalyzerPlugin detached")
        stopAnalyzer()
        collectorJob?.cancel()
        collectorJob = null
        this.stateHolder = null
        this.cameraController = null
    }

    /**
     * Convenience method to attach this plugin to a state holder.
     * Use this when manually managing plugin lifecycle.
     *
     * @param stateHolder The state holder to attach to.
     */
    fun attachToStateHolder(stateHolder: CameraKStateHolder) {
        stateHolder.attachPlugin(this)
    }

    /**
     * Returns a flow that emits latest frames.
     *
     * @return SharedFlow<ByteArray>
     */
    fun getAnalyzerFlow() = analyzerFlow.asSharedFlow()
}

expect fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit)

@Composable
fun rememberAnalyzerPlugin(coroutineScope: CoroutineScope = rememberCoroutineScope()): AnalyzerPlugin = remember {
    AnalyzerPlugin(coroutineScope)
}
