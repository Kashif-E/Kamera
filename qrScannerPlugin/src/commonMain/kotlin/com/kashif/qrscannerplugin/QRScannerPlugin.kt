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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.jvm.JvmOverloads

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
class QRScannerPlugin internal constructor(
    private val coroutineScope: CoroutineScope,
    private val startScanner: (CameraController, (String) -> Unit) -> ScannerHandle,
) : CameraKPlugin {
    /**
     * @param coroutineScope Scope for managing QR scanning operations and event emission.
     * @param scanIntervalMs The shortest time between two decode attempts, whether or not the last
     *   one found a code, on Android and desktop. Defaults to 200 ms. On iOS the system's metadata
     *   output decides how often it looks for codes, and this is ignored.
     */
    @JvmOverloads
    constructor(coroutineScope: CoroutineScope, scanIntervalMs: Long = DEFAULT_QR_SCAN_INTERVAL_MS) : this(
        coroutineScope,
        { controller, onQrScanner -> startQrScanning(controller, scanIntervalMs, onQrScanner) },
    ) {
        require(scanIntervalMs >= 0) { "scanIntervalMs must not be negative" }
    }

    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null
    private val qrCodeFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var isScanning = atomic(false)

    // What the app asked for: scanning, unless it paused. A new Ready starts a scanner only while this
    // is true, so a pause holds across camera re-initialisation until the app resumes.
    private val scanningWanted = atomic(true)
    private var collectorJob: Job? = null
    private var scannerHandle: ScannerHandle? = null

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
                        if (scanningWanted.value) registerScanner()
                    }
            }
    }

    /**
     * Detaches the plugin from the state holder and cleans up resources.
     */
    override fun onDetach() {
        CameraKLogger.d("CameraK", "QRScannerPlugin detached")
        isScanning.value = false
        scannerHandle?.stop()
        scannerHandle = null
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
     * Starts scanning, now if the camera is ready and otherwise when it becomes ready. Same as
     * [resumeScanning].
     */
    fun startScanning() {
        scanningWanted.value = true
        registerScanner()
    }

    private fun registerScanner() {
        val controller = cameraController ?: run {
            CameraKLogger.d("CameraK", "QRScannerPlugin: CameraController is not initialized")
            isScanning.value = false
            return
        }
        // Tear down any previous registration so re-init (new Ready) doesn't stack analyzers/delegates.
        scannerHandle?.stop()
        isScanning.value = true
        scannerHandle = try {
            startScanner(controller) { qrCode ->
                if (isScanning.value) {
                    qrCodeFlow.tryEmit(qrCode)
                    // Also surface as a state-holder event (emitEvent is suspend → launch on the
                    // owned plugin scope so it's cancelled on shutdown, not the plugin's own scope).
                    stateHolder?.let { holder ->
                        holder.pluginScope.launch { holder.emitEvent(CameraKEvent.QRCodeScanned(qrCode)) }
                    }
                }
            }
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "QRScannerPlugin: Failed to start scanning", e)
            isScanning.value = false
            stateHolder?.let { holder -> holder.pluginScope.launch { holder.emitEvent(CameraKEvent.PluginFailed(e)) } }
            null
        }
    }

    /**
     * Pauses scanning: the platform scanner is removed, so no frame is decoded until [resumeScanning].
     * The pause holds when the camera re-initialises.
     */
    fun pauseScanning() {
        scanningWanted.value = false
        isScanning.value = false
        scannerHandle?.stop()
        scannerHandle = null
    }

    /**
     * Resumes scanning after [pauseScanning], now if the camera is ready and otherwise when it
     * becomes ready.
     */
    fun resumeScanning() {
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
/**
 * Handle to active scanning. [stop] removes the underlying platform analyzer/delegate so the
 * camera stops decoding frames — the plugin keeps no other way to tear that registration down.
 */
fun interface ScannerHandle {
    fun stop()
}

expect fun startScanning(controller: CameraController, onQrScanner: (String) -> Unit): ScannerHandle

/** The decode-attempt interval [QRScannerPlugin] and [startScanning] use unless told otherwise. */
internal const val DEFAULT_QR_SCAN_INTERVAL_MS = 200L

/**
 * Starts scanning with at most one decode attempt per [scanIntervalMs], where the platform decodes
 * frames itself (Android and desktop).
 */
internal expect fun startQrScanning(
    controller: CameraController,
    scanIntervalMs: Long,
    onQrScanner: (String) -> Unit,
): ScannerHandle

/**
 * Creates and remembers a QRScannerPlugin composable.
 *
 * @param coroutineScope Scope for managing QR scanning operations and event emission.
 * @param scanIntervalMs The shortest time between two decode attempts on Android and desktop; see
 *   [QRScannerPlugin].
 * @return A remembered instance of QRScannerPlugin.
 */
@Composable
fun rememberQRScannerPlugin(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    scanIntervalMs: Long = DEFAULT_QR_SCAN_INTERVAL_MS,
): QRScannerPlugin = remember(scanIntervalMs) {
    QRScannerPlugin(coroutineScope, scanIntervalMs)
}
