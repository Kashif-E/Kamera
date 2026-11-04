package com.kashif.cameraK.state

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode

/**
 * The camera's low-rate properties as the platform reports them. The holder derives every field;
 * nothing else writes it, so a command shows here only once the platform has applied it. Before the
 * first bind the camera fields are null and DEFAULT; the values are confirmed once `cameraState` is
 * Ready.
 *
 * camerak-core carries no Compose annotations; camerak's compose-stability.conf marks this class
 * stable for Compose, so only changed properties trigger recomposition.
 *
 * @property zoomLevel The zoom ratio. Deprecated: read `holder.zoom`.
 * @property maxZoom The largest zoom ratio. Deprecated: read `holder.zoom`.
 * @property flashMode The flash mode for captures.
 * @property torchMode The torch mode.
 * @property cameraLens The bound camera's facing; null before the first bind.
 * @property imageFormat The configured image format. Deprecated: read the configuration.
 * @property qualityPrioritization The configured trade-off. Deprecated: read the configuration.
 * @property cameraDeviceType The bound camera's device type; DEFAULT before the first bind.
 * @property isCapturing Whether a capture is in flight: [pendingCaptures] is above zero.
 * @property lastError Always null: a command returns its failure. Deprecated.
 * @property isRecording Whether a recording is Recording or Paused. Deprecated: read `holder.recordingState`.
 * @property isPaused Whether the recording is Paused. Deprecated: read `holder.recordingState`.
 * @property recordingDurationMs The recorded time, paused stretches taken out, updated four times a
 *   second; 0 when no recording is live. Deprecated: read `holder.recordingState.value.elapsed()`.
 * @property pendingCaptures How many captures are in flight.
 * @property cameraId The bound camera's `LensInfo.id`; null before the first bind.
 * @property targetOrientation The orientation outputs are fixed to; null follows the device.
 *
 * @example
 * ```kotlin
 * val holder = rememberCameraKStateHolder()
 * val uiState by holder.uiState.collectAsState()
 * val scope = rememberCoroutineScope()
 * IconButton(onClick = { scope.launch { holder.cycleFlashMode() } }, enabled = !uiState.isCapturing) {
 *     Icon(
 *         imageVector = when (uiState.flashMode) {
 *             FlashMode.ON -> Icons.Default.FlashOn
 *             FlashMode.AUTO -> Icons.Default.FlashAuto
 *             else -> Icons.Default.FlashOff
 *         },
 *         contentDescription = "Flash",
 *     )
 * }
 * ```
 */
data class CameraUIState(
    @Deprecated("Read the ratio from the holder's zoom flow.", ReplaceWith("holder.zoom.value.ratio"))
    val zoomLevel: Float = 1.0f,
    @Deprecated("Read the range from the holder's zoom flow.", ReplaceWith("holder.zoom.value.maxRatio"))
    val maxZoom: Float = 1.0f,
    val flashMode: FlashMode? = null,
    val torchMode: TorchMode? = null,
    val cameraLens: CameraLens? = null,
    @Deprecated("A configuration value: read CameraConfiguration.imageFormat.")
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    @Deprecated("A configuration value: read CameraConfiguration.qualityPrioritization.")
    val qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED,
    val cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    val isCapturing: Boolean = false,
    @Deprecated("Always null: every command returns its failure as its result.")
    val lastError: String? = null,
    @Deprecated("Read the holder's recordingState: Recording or Paused.")
    val isRecording: Boolean = false,
    @Deprecated("Read the holder's recordingState: Paused.")
    val isPaused: Boolean = false,
    @Deprecated("Read the holder's recordingState: recordingState.value.elapsed() is the recorded time.")
    val recordingDurationMs: Long = 0L,
    val pendingCaptures: Int = 0,
    val cameraId: String? = null,
    val targetOrientation: DeviceOrientation? = null,
)
