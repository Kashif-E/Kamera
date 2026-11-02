package com.kashif.cameraK.state

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode

/**
 * Immutable UI state for camera properties.
 * All properties are observable and update automatically when camera configuration changes.
 *
 * camerak-core carries no Compose annotations; camerak's compose-stability.conf marks this class
 * stable for Compose, so only changed properties trigger recomposition.
 *
 * @property zoomLevel Current zoom level (1.0 = no zoom, higher values = zoomed in).
 * @property maxZoom Maximum zoom level supported by the camera hardware.
 * @property flashMode Current flash mode setting.
 * @property torchMode Current torch/flashlight mode setting.
 * @property cameraLens Current camera lens (FRONT or BACK).
 * @property imageFormat Current image format (JPEG or PNG).
 * @property qualityPrioritization Current quality prioritization strategy.
 * @property cameraDeviceType Current camera device type (WIDE_ANGLE, TELEPHOTO, etc.).
 * @property isCapturing Whether a capture operation is currently in progress.
 * @property lastError Error message if an operation failed, null otherwise.
 *
 * @example
 * ```kotlin
 * val cameraState by rememberCameraKState()
 * when (val state = cameraState) {
 *     is CameraKState.Ready -> {
 *         val uiState = state.uiState
 *         Text("Zoom: ${uiState.zoomLevel}x / ${uiState.maxZoom}x")
 *         IconButton(
 *             onClick = { state.controller.toggleFlashMode() },
 *             enabled = !uiState.isCapturing
 *         ) {
 *             Icon(
 *                 imageVector = when (uiState.flashMode) {
 *                     FlashMode.ON -> Icons.Default.FlashOn
 *                     FlashMode.OFF -> Icons.Default.FlashOff
 *                     FlashMode.AUTO -> Icons.Default.FlashAuto
 *                     null -> Icons.Default.FlashOff
 *                 },
 *                 contentDescription = "Flash"
 *             )
 *         }
 *     }
 *     else -> {}
 * }
 * ```
 */
data class CameraUIState(
    val zoomLevel: Float = 1.0f,
    val maxZoom: Float = 1.0f,
    val flashMode: FlashMode? = null,
    val torchMode: TorchMode? = null,
    val cameraLens: CameraLens? = null,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED,
    val cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    val isCapturing: Boolean = false,
    val lastError: String? = null,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingDurationMs: Long = 0L,
)
