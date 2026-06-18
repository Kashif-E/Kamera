package com.kashif.cameraK.state

import androidx.compose.runtime.Stable
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Pure Kotlin state holder for camera operations.
 * This class is the reactive bridge between platform-specific [CameraController]
 * and Compose UI layer.
 *
 * **Architecture Layer:** Layer 2 (State Holder)
 * - No Compose dependencies (testable in pure Kotlin)
 * - Manages [CameraController] lifecycle
 * - Exposes reactive state via [StateFlow]
 * - Emits one-shot events via [SharedFlow]
 * - Manages plugin lifecycle
 *
 * **Thread Safety:** All public methods are thread-safe.
 * **Lifecycle:** Initialize with [initialize()], cleanup with [shutdown()].
 *
 * @param cameraConfiguration Initial camera configuration.
 * @param controllerFactory Factory function to create platform-specific [CameraController].
 * @param coroutineScope CoroutineScope for managing async operations.
 *
 * @example
 * ```kotlin
 * val stateHolder = CameraKStateHolder(
 *     cameraConfiguration = CameraConfiguration(),
 *     controllerFactory = { /* create controller */ }
 * )
 *
 * // Initialize
 * stateHolder.initialize()
 *
 * // Observe state
 * stateHolder.cameraState.collect { state ->
 *     when (state) {
 *         is CameraKState.Ready -> /* use controller */
 *         is CameraKState.Error -> /* handle error */
 *         CameraKState.Initializing -> /* show loading */
 *     }
 * }
 *
 * // Cleanup
 * stateHolder.shutdown()
 * ```
 */
@Stable
class CameraKStateHolder(
    private val cameraConfiguration: CameraConfiguration,
    private val controllerFactory: suspend () -> CameraController,
    private val coroutineScope: CoroutineScope,
) {
    // ═══════════════════════════════════════════════════════════════
    // State Management
    // ═══════════════════════════════════════════════════════════════

    private val _cameraState = MutableStateFlow<CameraKState>(CameraKState.Initializing)

    /**
     * Observable camera lifecycle state.
     * Emits [CameraKState.Initializing], [CameraKState.Ready], or [CameraKState.Error].
     */
    val cameraState: StateFlow<CameraKState> = _cameraState.asStateFlow()

    private val _uiState = MutableStateFlow(CameraUIState())

    /**
     * Observable UI state containing camera properties.
     * Updates automatically when camera properties change.
     */
    val uiState: StateFlow<CameraUIState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CameraKEvent>()

    /**
     * One-shot events emitted by the camera system.
     * Events are not persisted - collect to handle them.
     */
    val events: SharedFlow<CameraKEvent> = _events.asSharedFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)

    /**
     * Observable device orientation.
     * Updates automatically when the physical device orientation changes.
     */
    val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    /**
     * CoroutineScope for plugins to launch their operations.
     *
     * This is a child of the scope passed to the StateHolder (so it dies with the host, e.g. when
     * the composable leaves composition), but it is owned here: [shutdown] cancels every coroutine
     * launched in it. A failure in one plugin's coroutine is isolated (SupervisorJob) and does not
     * tear down the others. Plugins should still cancel their own jobs in [CameraKPlugin.onDetach].
     *
     * @example
     * ```kotlin
     * override fun onAttach(stateHolder: CameraKStateHolder) {
     *     stateHolder.pluginScope.launch {
     *         stateHolder.cameraState
     *             .filterIsInstance<CameraKState.Ready>()
     *             .collect { ready ->
     *                 startScanning(ready.controller)
     *             }
     *     }
     * }
     * ```
     */
    val pluginScope: CoroutineScope =
        CoroutineScope(coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job]))

    // ═══════════════════════════════════════════════════════════════
    // Internal State
    // ═══════════════════════════════════════════════════════════════

    private var controller: CameraController? = null

    // Guards attachedPlugins: attach/detach/initialize/shutdown can run from different threads.
    private val pluginLock = SynchronizedObject()
    private val attachedPlugins = mutableListOf<CameraKPlugin>()
    private var isInitialized = false
    private var recordingTimerJob: Job? = null
    private var recordingFilePath: String? = null

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initializes the camera controller and starts the session.
     * Safe to call multiple times - subsequent calls are no-ops.
     *
     * @throws Exception if controller creation fails.
     */
    suspend fun initialize() {
        if (isInitialized) return

        try {
            _cameraState.value = CameraKState.Initializing

            // Create controller
            val newController = controllerFactory()
            controller = newController

            // Start session
            newController.startSession()

            // Initialize orientation tracking
            _deviceOrientation.value = newController.getDeviceOrientation()
            newController.setOnOrientationChangedListener { orientation ->
                _deviceOrientation.value = orientation
            }

            // Initialize UI state from controller
            updateUIStateFromController(newController)

            // Flip isInitialized AND snapshot the plugins atomically. This closes the race with a
            // concurrent attachPlugin(): it either lands in this snapshot (added before the lock, so
            // initialize attaches it) or observes isInitialized == true (added after) and attaches
            // itself — never neither. onAttach runs outside the lock so it can't deadlock/stall it.
            val plugins = synchronized(pluginLock) {
                isInitialized = true
                attachedPlugins.toList()
            }
            plugins.forEach { plugin -> plugin.onAttach(this) }

            // Update to ready state
            _cameraState.value =
                CameraKState.Ready(
                    controller = newController,
                    uiState = _uiState.value,
                )
        } catch (e: Exception) {
            synchronized(pluginLock) { isInitialized = false }
            _cameraState.value =
                CameraKState.Error(
                    exception = e,
                    message = "Failed to initialize camera: ${e.message}",
                    isRetryable = true,
                )
            throw e
        }
    }

    /**
     * Shuts down the camera controller and releases resources.
     * Safe to call multiple times.
     */
    fun shutdown() {
        if (!isInitialized) return

        try {
            // Detach plugins BEFORE resetting recording state, so a recorder plugin can still see
            // isRecording == true in onDetach and stop the recording (emitting RecordingStopped)
            // before we tear the controller down. (Snapshot+clear under lock; onDetach outside it.)
            val plugins = synchronized(pluginLock) {
                val snapshot = attachedPlugins.toList()
                attachedPlugins.clear()
                snapshot
            }
            plugins.forEach { plugin -> plugin.onDetach() }

            // Now reset recording UI state (cancels the duration timer).
            resetRecordingState()

            // Cancel any coroutines plugins launched in pluginScope (belt-and-suspenders: onDetach
            // should already cancel them, but this guarantees nothing leaks past shutdown).
            pluginScope.coroutineContext[Job]?.cancelChildren()

            // Stop orientation listener and session
            controller?.setOnOrientationChangedListener(null)
            controller?.stopSession()
            controller?.cleanup()
            controller = null

            isInitialized = false
            _cameraState.value = CameraKState.Initializing
        } catch (e: Exception) {
            // Log error but don't throw during shutdown
            _uiState.value =
                _uiState.value.copy(
                    lastError = "Shutdown error: ${e.message}",
                )
        }
    }

    /**
     * Suspends until camera becomes READY, then returns the controller.
     * Useful for plugins that prefer to wait rather than observe streams.
     *
     * Returns null if camera encounters an error before becoming ready.
     *
     * @example
     * ```kotlin
     * override fun onAttach(stateHolder: CameraKStateHolder) {
     *     stateHolder.pluginScope.launch {
     *         val controller = stateHolder.getReadyCameraController()
     *         controller?.let { setupPlugin(it) }
     *     }
     * }
     * ```
     */
    suspend fun getReadyCameraController(): CameraController? {
        // Suspend until the camera resolves either way; return null on Error instead of hanging forever.
        val state = cameraState.first { it is CameraKState.Ready || it is CameraKState.Error }
        return (state as? CameraKState.Ready)?.controller
    }

    // ═══════════════════════════════════════════════════════════════
    // Plugin Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Attaches a plugin to the camera controller.
     * If controller is already initialized, plugin is attached immediately.
     * Otherwise, plugin will be attached during initialization.
     *
     * @param plugin The [CameraKPlugin] to attach.
     */
    fun attachPlugin(plugin: CameraKPlugin) {
        // Read isInitialized under the same lock that initialize() uses when it flips the flag +
        // snapshots, so this plugin is either in that snapshot or attached here — never dropped.
        val attachNow = synchronized(pluginLock) {
            if (attachedPlugins.contains(plugin)) return
            attachedPlugins.add(plugin)
            isInitialized
        }

        if (attachNow) {
            plugin.onAttach(this)
        }
    }

    /**
     * Detaches a plugin from the camera controller.
     *
     * @param plugin The [CameraKPlugin] to detach.
     */
    fun detachPlugin(plugin: CameraKPlugin) {
        val removed = synchronized(pluginLock) { attachedPlugins.remove(plugin) }
        if (removed) {
            plugin.onDetach()
        }
    }

    /**
     * Gets the current [CameraController] instance if available.
     *
     * ⚠️ **Deprecated for new plugins:** Use `getReadyCameraController()` or observe `cameraState` instead.
     * This method returns null if camera hasn't initialized yet, causing plugins to fail.
     *
     * @return The controller, or null if not initialized.
     * @see getReadyCameraController For plugins that need to wait for readiness
     * @see cameraState To observe camera state and auto-activate
     */
    fun getController(): CameraController? = controller

    // ═══════════════════════════════════════════════════════════════
    // Camera Operations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Captures an image and saves it to a file.
     * Emits [CameraKEvent.ImageCaptured] on success or [CameraKEvent.CaptureFailed] on failure.
     */
    fun captureImage() {
        val currentController =
            controller ?: run {
                coroutineScope.launch {
                    _events.emit(
                        CameraKEvent.CaptureFailed(
                            Exception("Camera not initialized"),
                        ),
                    )
                }
                return
            }

        coroutineScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isCapturing = true)

                val result = currentController.takePictureToFile()

                _events.emit(CameraKEvent.ImageCaptured(result))
            } catch (e: Exception) {
                _events.emit(CameraKEvent.CaptureFailed(e))
                _uiState.value =
                    _uiState.value.copy(
                        lastError = "Capture failed: ${e.message}",
                    )
            } finally {
                _uiState.value = _uiState.value.copy(isCapturing = false)
            }
        }
    }

    /**
     * Sets the zoom level.
     *
     * @param zoom Zoom ratio (1.0 = no zoom, higher values = zoomed in).
     */
    fun setZoom(zoom: Float) {
        val currentController = controller ?: return

        currentController.setZoom(zoom)
        _uiState.value =
            _uiState.value.copy(
                zoomLevel = currentController.getZoom(),
            )
    }

    /**
     * Toggles the flash mode (OFF → AUTO → ON → OFF).
     */
    fun toggleFlashMode() {
        val currentController = controller ?: return

        currentController.toggleFlashMode()
        _uiState.value =
            _uiState.value.copy(
                flashMode = currentController.getFlashMode(),
            )
    }

    /**
     * Sets the flash mode.
     *
     * @param mode The desired flash mode.
     */
    fun setFlashMode(mode: com.kashif.cameraK.enums.FlashMode) {
        val currentController = controller ?: return

        currentController.setFlashMode(mode)
        _uiState.value = _uiState.value.copy(flashMode = mode)
    }

    /**
     * Toggles the torch mode (OFF → ON → OFF).
     */
    fun toggleTorchMode() {
        val currentController = controller ?: return

        currentController.toggleTorchMode()
        _uiState.value =
            _uiState.value.copy(
                torchMode = currentController.getTorchMode(),
            )
    }

    /**
     * Sets the torch mode.
     *
     * @param mode The desired torch mode.
     */
    fun setTorchMode(mode: com.kashif.cameraK.enums.TorchMode) {
        val currentController = controller ?: return

        currentController.setTorchMode(mode)
        _uiState.value = _uiState.value.copy(torchMode = mode)
    }

    /**
     * Toggles the camera lens (FRONT ↔ BACK).
     */
    fun toggleCameraLens() {
        val currentController = controller ?: return

        currentController.toggleCameraLens()
        _uiState.value =
            _uiState.value.copy(
                cameraLens = currentController.getCameraLens(),
            )
    }

    // ═══════════════════════════════════════════════════════════════
    // Orientation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Locks camera output orientation to a specific value.
     * Affects image capture and video recording rotation.
     * Pass `null` to follow the device's physical orientation automatically.
     */
    fun setTargetOrientation(orientation: DeviceOrientation?) {
        controller?.setTargetOrientation(orientation)
    }

    // ═══════════════════════════════════════════════════════════════
    // Video Recording Operations
    // ═══════════════════════════════════════════════════════════════

    private fun resetRecordingState() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        recordingFilePath = null
        _uiState.value =
            _uiState.value.copy(
                isRecording = false,
                isPaused = false,
                recordingDurationMs = 0L,
            )
    }

    /**
     * Starts video recording.
     * Emits [CameraKEvent.RecordingStarted] on success.
     * If [VideoConfiguration.maxDurationMs] > 0, auto-stops and emits
     * [CameraKEvent.RecordingMaxDurationReached].
     *
     * @param configuration Recording settings.
     */
    @OptIn(ExperimentalTime::class)
    fun startRecording(configuration: VideoConfiguration = VideoConfiguration()) {
        // Guard against double-start: a second start while recording would orphan the timer and the
        // platform recorder (leaking encoders/file handles) and confuse the duration/state.
        if (_uiState.value.isRecording) {
            coroutineScope.launch {
                _events.emit(CameraKEvent.RecordingFailed(Exception("Recording already in progress")))
            }
            return
        }

        val currentController =
            controller ?: run {
                coroutineScope.launch {
                    _events.emit(
                        CameraKEvent.RecordingFailed(
                            Exception("Camera not initialized"),
                        ),
                    )
                }
                return
            }

        coroutineScope.launch {
            try {
                val path = currentController.startRecording(configuration)
                recordingFilePath = path
                _uiState.value =
                    _uiState.value.copy(
                        isRecording = true,
                        isPaused = false,
                        recordingDurationMs = 0L,
                    )
                _events.emit(CameraKEvent.RecordingStarted(path))

                // Start duration ticker with pause-aware elapsed tracking
                recordingTimerJob = coroutineScope.launch {
                    val startMs = Clock.System.now().toEpochMilliseconds()
                    var pausedAccumulatorMs = 0L
                    var pauseStartMs = 0L
                    while (isActive) {
                        delay(250L)
                        val isPaused = _uiState.value.isPaused
                        val wasPaused = pauseStartMs > 0L
                        if (isPaused && !wasPaused) {
                            pauseStartMs = Clock.System.now().toEpochMilliseconds()
                        } else if (!isPaused && wasPaused) {
                            pausedAccumulatorMs += Clock.System.now().toEpochMilliseconds() - pauseStartMs
                            pauseStartMs = 0L
                        }
                        if (isPaused) continue
                        val elapsed = Clock.System.now().toEpochMilliseconds() - startMs - pausedAccumulatorMs
                        _uiState.value = _uiState.value.copy(recordingDurationMs = elapsed)

                        if (configuration.maxDurationMs > 0 && elapsed >= configuration.maxDurationMs) {
                            // Auto-stop — guard against race with manual stopRecording()
                            if (!_uiState.value.isRecording) break
                            val result = currentController.stopRecording()
                            resetRecordingState()
                            _events.emit(CameraKEvent.RecordingMaxDurationReached(path, elapsed))
                            _events.emit(CameraKEvent.RecordingStopped(result))
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                resetRecordingState()
                _uiState.value =
                    _uiState.value.copy(
                        lastError = "Recording failed: ${e.message}",
                    )
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /**
     * Stops the active video recording.
     * Emits [CameraKEvent.RecordingStopped] with the result.
     */
    fun stopRecording() {
        if (!_uiState.value.isRecording) return
        val currentController = controller ?: return

        coroutineScope.launch {
            try {
                // Cancel timer first to prevent auto-stop race
                recordingTimerJob?.cancel()
                recordingTimerJob = null
                val result = currentController.stopRecording()
                resetRecordingState()
                _events.emit(CameraKEvent.RecordingStopped(result))
            } catch (e: Exception) {
                resetRecordingState()
                _uiState.value =
                    _uiState.value.copy(
                        lastError = "Stop recording failed: ${e.message}",
                    )
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /**
     * Pauses the active video recording.
     */
    fun pauseRecording() {
        val currentController = controller ?: return

        coroutineScope.launch {
            try {
                currentController.pauseRecording()
                _uiState.value = _uiState.value.copy(isPaused = true)
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        lastError = "Pause recording failed: ${e.message}",
                    )
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /**
     * Resumes a paused video recording.
     */
    fun resumeRecording() {
        val currentController = controller ?: return

        coroutineScope.launch {
            try {
                currentController.resumeRecording()
                _uiState.value = _uiState.value.copy(isPaused = false)
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        lastError = "Resume recording failed: ${e.message}",
                    )
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /**
     * Emits a custom event.
     * Used by plugins to communicate events to the UI layer.
     *
     * @param event The event to emit.
     */
    suspend fun emitEvent(event: CameraKEvent) {
        _events.emit(event)
    }

    /**
     * Updates UI state properties.
     * Used by plugins to update camera state.
     *
     * @param update Lambda to modify the current UI state.
     */
    fun updateUIState(update: (CameraUIState) -> CameraUIState) {
        _uiState.value = update(_uiState.value)
    }

    // ═══════════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun updateUIStateFromController(controller: CameraController) {
        _uiState.value =
            CameraUIState(
                zoomLevel = controller.getZoom(),
                maxZoom = controller.getMaxZoom(),
                flashMode = controller.getFlashMode(),
                torchMode = controller.getTorchMode(),
                cameraLens = controller.getCameraLens(),
                imageFormat = controller.getImageFormat(),
                qualityPrioritization = controller.getQualityPrioritization(),
                cameraDeviceType = controller.getPreferredCameraDeviceType(),
                isCapturing = false,
                lastError = null,
            )
    }
}

/**
 * Interface that all camera plugins must implement.
 *
 * Plugins extend camera functionality (QR scanning, OCR, face detection, etc.).
 * The StateHolder manages plugin lifecycle automatically.
 *
 * **Auto-Activation Pattern (Recommended):**
 * Plugins observe camera state and self-activate when Ready.
 *
 * @example
 * ```kotlin
 * class QRScannerPlugin : CameraKPlugin {
 *     private var scanningJob: Job? = null
 *
 *     override fun onAttach(stateHolder: CameraKStateHolder) {
 *         // Self-activate when camera becomes Ready
 *         scanningJob = stateHolder.pluginScope.launch {
 *             stateHolder.cameraState
 *                 .filterIsInstance<CameraKState.Ready>()
 *                 .collect { ready ->
 *                     startScanning(ready.controller)
 *                 }
 *         }
 *     }
 *
 *     override fun onDetach() {
 *         scanningJob?.cancel()
 *     }
 * }
 * ```
 *
 * **Alternative Pattern (Suspend Until Ready):**
 * For simpler plugins, wait for camera ready before setting up.
 *
 * @example
 * ```kotlin
 * class SimpleSaverPlugin : CameraKPlugin {
 *     override fun onAttach(stateHolder: CameraKStateHolder) {
 *         stateHolder.pluginScope.launch {
 *             val controller = stateHolder.getReadyCameraController()
 *             controller?.setupSaving()
 *         }
 *     }
 *
 *     override fun onDetach() {}
 * }
 * ```
 *
 *     private suspend fun onQRCodeDetected(qrCode: String) {
 *         stateHolder?.emitEvent(CameraKEvent.QRCodeScanned(qrCode))
 *     }
 * }
 * ```
 */
@Stable
interface CameraKPlugin {
    /**
     * Called when the plugin is attached to a camera controller.
     * Initialize plugin resources here.
     *
     * @param stateHolder The [CameraKStateHolder] to interact with.
     */
    fun onAttach(stateHolder: CameraKStateHolder)

    /**
     * Called when the plugin is detached from the camera controller.
     * Clean up plugin resources here.
     */
    fun onDetach()
}
