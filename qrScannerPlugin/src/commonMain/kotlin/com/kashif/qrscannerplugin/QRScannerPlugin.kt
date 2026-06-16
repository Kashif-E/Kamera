package com.kashif.qrscannerplugin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * QR Scanner plugin for the Compose-first camera API.
 *
 * ```kotlin
 * val scope = rememberCoroutineScope()
 * val qrPlugin = remember { QRScannerPlugin(scope) }
 * var detectedQR by remember { mutableStateOf<String?>(null) }
 *
 * val cameraState by rememberCameraKState { stateHolder ->
 *     qrPlugin.attachToStateHolder(stateHolder)
 * }
 *
 * LaunchedEffect(qrPlugin) {
 *     qrPlugin.getQrCodeFlow().collect { qr ->
 *         detectedQR = qr
 *     }
 * }
 * ```
 *
 * @property coroutineScope Scope for managing QR scanning operations and event emission.
 */
@Stable
class QRScannerPlugin(private val coroutineScope: CoroutineScope) : CameraKPlugin {
    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null
    private val qrCodeFlow = MutableSharedFlow<String>()
    private var isScanning = atomic(false)
    private var collectorJob: kotlinx.coroutines.Job? = null

    /**
     * Attaches the plugin to the state holder (new API).
     * Automatically starts scanning when camera becomes ready.
     *
     * @param stateHolder The [CameraKStateHolder] to attach to.
     */
    override fun onAttach(stateHolder: CameraKStateHolder) {
        CameraKLogger.d("CameraK", "QRScannerPlugin attached (new API)")
        this.stateHolder = stateHolder

        collectorJob =
            stateHolder.pluginScope.launch {
                stateHolder.cameraState
                    .filterIsInstance<CameraKState.Ready>()
                    .collect { readyState ->
                        this@QRScannerPlugin.cameraController = readyState.controller
                        startScanning()
                    }
            }
    }

    /**
     * Detaches the plugin from the state holder and cleans up resources.
     */
    override fun onDetach() {
        CameraKLogger.d("CameraK", "QRScannerPlugin detached")
        pauseScanning()
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
     * Starts the QR code scanning process.
     *
     * @throws IllegalStateException If the CameraController is not initialized.
     */
    fun startScanning() {
        cameraController?.let { controller ->
            isScanning.value = true
            try {
                startScanning(controller = controller) { qrCode ->
                    if (isScanning.value) {
                        coroutineScope.launch {
                            qrCodeFlow.emit(qrCode)
                            // Emit event to state holder if available (new API)
                            stateHolder?.emitEvent(CameraKEvent.QRCodeScanned(qrCode))
                        }
                    }
                }
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "QRScannerPlugin: Failed to start scanning: ${e.message}")
                isScanning.value = false
                // Camera might not be fully initialized yet - will retry on next opportunity
            }
        } ?: run {
            CameraKLogger.d("CameraK", "QRScannerPlugin: CameraController is not initialized")
            isScanning.value = false
        }
    }

    /**
     * Pauses the QR code scanning process.
     */
    fun pauseScanning() {
        isScanning.value = false
    }

    /**
     * Resumes the QR code scanning process.
     */
    fun resumeScanning() {
        isScanning.value = true
        startScanning()
    }

    /**
     * Returns a flow that emits QR codes.
     *
     * @return SharedFlow<String>
     */
    fun getQrCodeFlow() = qrCodeFlow.asSharedFlow()
}

/**
 * Platform-specific function to start scanning for QR codes.
 *
 * @param controller The CameraController to be used for scanning.
 * @param onQrScanner A callback function that is invoked when a QR code is scanned.
 */
expect fun startScanning(controller: CameraController, onQrScanner: (String) -> Unit)

/**
 * Creates and remembers a QRScannerPlugin composable.
 *
 * @param onQrScanner A callback function that is invoked when a QR code is scanned.
 * @return A remembered instance of QRScannerPlugin.
 */
@Composable
fun rememberQRScannerPlugin(coroutineScope: CoroutineScope = rememberCoroutineScope()): QRScannerPlugin = remember {
    QRScannerPlugin(coroutineScope)
}
