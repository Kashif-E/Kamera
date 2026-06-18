package com.kashif.analyzerPlugin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Handle to a started analyzer. Call [stop] to remove the underlying platform analyzer/output so it
 * stops consuming frames — the plugin keeps no other way to tear that registration down.
 */
fun interface AnalyzerHandle {
    fun stop()
}

class AnalyzerPlugin(val coroutineScope: CoroutineScope) : CameraKPlugin {
    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null
    private val analyzerFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var isAnalyzing = atomic(false)
    private var collectorJob: Job? = null
    private var analyzerHandle: AnalyzerHandle? = null

    fun startAnalyzer() {
        val controller = cameraController ?: return
        // Tear down any previous registration first so re-init (new Ready) doesn't stack
        // analyzers (Android) or outputs (iOS), which would multiply per-frame work and leak.
        analyzerHandle?.stop()
        isAnalyzing.value = true
        analyzerHandle = startAnalyzer(controller) { frame ->
            if (isAnalyzing.value) analyzerFlow.tryEmit(frame)
        }
    }

    fun stopAnalyzer() {
        isAnalyzing.value = false
        analyzerHandle?.stop()
        analyzerHandle = null
    }

    /**
     * Attaches the plugin to the state holder (new API).
     * Automatically starts Analyzer when camera becomes ready.
     *
     * @param stateHolder The [CameraKStateHolder] to attach to.
     */
    override fun onAttach(stateHolder: CameraKStateHolder) {
        CameraKLogger.d("CameraK", "Analyzer attached (new API)")
        this.stateHolder = stateHolder

        collectorJob = stateHolder.pluginScope.launch {
            stateHolder.cameraState
                .filterIsInstance<CameraKState.Ready>()
                .collect { readyState ->
                    try {
                        this@AnalyzerPlugin.cameraController = readyState.controller
                        startAnalyzer()
                    } catch (e: Exception) {
                        CameraKLogger.e("CameraK", "Analyzer: Failed to start analyzer: ${e.message}")
                        CameraKLogger.e("CameraK", "Unhandled exception", e)
                    }
                }
        }
    }

    /**
     * Detaches the plugin from the state holder and cleans up resources.
     */
    override fun onDetach() {
        CameraKLogger.d("CameraK", "com.kashif.analyzerPlugin.AnalyzerPlugin detached")
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

expect fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit): AnalyzerHandle

@Composable
fun rememberAnalyzerPlugin(coroutineScope: CoroutineScope = rememberCoroutineScope()): AnalyzerPlugin =
    remember(coroutineScope) {
        AnalyzerPlugin(coroutineScope)
    }
