package com.kashif.cameraK.state

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
