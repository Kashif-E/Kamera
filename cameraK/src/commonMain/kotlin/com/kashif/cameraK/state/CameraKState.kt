package com.kashif.cameraK.state

import androidx.compose.runtime.Immutable
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.video.VideoCaptureResult

/**
 * Represents the lifecycle state of the camera system.
 * This sealed class provides compile-time type safety for handling different camera states.
 *
 * Use this in Compose to react to camera initialization status without callbacks.
 *
 * @example
 * ```kotlin
 * val cameraState by rememberCameraKState(configuration)
 * when (cameraState) {
 *     is CameraKState.Loading -> LoadingIndicator()
 *     is CameraKState.Ready -> CameraPreviewUI(cameraState.controller)
 *     is CameraKState.Error -> ErrorScreen(cameraState.exception)
 * }
 * ```
 */
@Immutable
sealed class CameraKState {
    /**
     * Camera is initializing. No controller available yet.
     * Show loading UI or progress indicator in this state.
     */
    @Immutable
    data object Initializing : CameraKState()

    /**
     * Camera is ready for use. Controller is available.
     *
     * @property controller The initialized [CameraController] instance.
     * @property uiState Current UI state of the camera (zoom, flash, lens, etc.).
     */
    @Immutable
    data class Ready(val controller: CameraController, val uiState: CameraUIState) : CameraKState()

    /**
     * Camera initialization or operation failed.
     *
     * @property exception The exception that caused the failure.
     * @property message User-friendly error message.
     * @property isRetryable Whether the operation can be retried.
     */
    @Immutable
    data class Error(val exception: Exception, val message: String, val isRetryable: Boolean = true) : CameraKState()
}

/**
 * Immutable UI state for camera properties.
 * All properties are observable and update automatically when camera configuration changes.
 *
 * This class is marked @Immutable to enable Compose smart recomposition.
 * Only changed properties will trigger recomposition, not the entire state.
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
@Immutable
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

/**
 * One-shot events emitted by the camera system.
 * Unlike state, events are not persisted and should be handled once.
 *
 * Use SharedFlow to collect these events.
 *
 * @example
 * ```kotlin
 * LaunchedEffect(stateHolder) {
 *     stateHolder.events.collect { event ->
 *         when (event) {
 *             is CameraKEvent.ImageCaptured -> showSuccessToast(event.result)
 *             is CameraKEvent.CaptureFailed -> showErrorDialog(event.exception)
 *             is CameraKEvent.QRCodeScanned -> navigateToDetails(event.qrCode)
 *             else -> {}
 *         }
 *     }
 * }
 * ```
 */
@Immutable
sealed class CameraKEvent {
    /**
     * No event (initial state).
     */
    @Immutable
    data object None : CameraKEvent()

    /**
     * Image capture completed successfully.
     *
     * @property result The captured image result (file path or byte array).
     */
    @Immutable
    data class ImageCaptured(val result: ImageCaptureResult) : CameraKEvent()

    /**
     * Image capture failed.
     *
     * @property exception The exception that caused the failure.
     */
    @Immutable
    data class CaptureFailed(val exception: Exception) : CameraKEvent()

    /**
     * QR code detected and scanned.
     *
     * @property qrCode The decoded QR code content.
     */
    @Immutable
    data class QRCodeScanned(val qrCode: String) : CameraKEvent()

    /**
     * OCR text recognition completed.
     *
     * @property text The recognized text.
     */
    @Immutable
    data class TextRecognized(val text: String) : CameraKEvent()

    /**
     * Camera permission denied by user.
     *
     * @property permission The denied permission name.
     */
    @Immutable
    data class PermissionDenied(val permission: String) : CameraKEvent()

    /**
     * Video recording started.
     *
     * @property filePath The output file path where the video is being recorded.
     */
    @Immutable
    data class RecordingStarted(val filePath: String) : CameraKEvent()

    /**
     * Video recording stopped (manually or due to error).
     *
     * @property result The result of the recording with file path and duration.
     */
    @Immutable
    data class RecordingStopped(val result: VideoCaptureResult) : CameraKEvent()

    /**
     * Video recording failed.
     *
     * @property exception The exception that caused the failure.
     */
    @Immutable
    data class RecordingFailed(val exception: Exception) : CameraKEvent()

    /**
     * Video recording reached its configured max duration and auto-stopped.
     *
     * @property filePath The output file path.
     * @property durationMs The actual duration in milliseconds.
     */
    @Immutable
    data class RecordingMaxDurationReached(val filePath: String, val durationMs: Long) : CameraKEvent()
}

/**
 * Configuration for camera initialization.
 * Immutable by design to ensure predictable camera setup.
 *
 * Use this to configure the camera before initialization.
 * Changes to configuration require camera reinitialization.
 *
 * @property flashMode Initial flash mode.
 * @property torchMode Initial torch mode.
 * @property cameraLens Initial camera lens.
 * @property imageFormat Image format for captures.
 * @property qualityPrioritization Quality vs performance trade-off.
 * @property cameraDeviceType Preferred camera device type.
 * @property aspectRatio Aspect ratio for preview and capture.
 * @property targetResolution Target resolution (width x height) or null for auto.
 * @property directory Directory for saving captured images.
 * @property mirrorFrontCamera If true, front-camera captures are horizontally mirrored to match
 *   the mirrored preview (selfie "what you see is what you get"). No effect on the back camera.
 *   Representation note: iOS and the Android byte-array path bake the flip into the pixels; the
 *   Android file path (`takePictureToFile`, the fast path) records it as an EXIF orientation tag,
 *   which EXIF-aware viewers honor but raw-pixel consumers may ignore.
 *
 * @example
 * ```kotlin
 * val config = CameraConfiguration(
 *     flashMode = FlashMode.AUTO,
 *     cameraLens = CameraLens.BACK,
 *     imageFormat = ImageFormat.JPEG,
 *     qualityPrioritization = QualityPrioritization.QUALITY,
 *     aspectRatio = AspectRatio.RATIO_16_9
 * )
 * val cameraState by rememberCameraKState(config)
 * ```
 */
@Immutable
data class CameraConfiguration(
    val flashMode: FlashMode = FlashMode.OFF,
    val torchMode: TorchMode = TorchMode.OFF,
    val cameraLens: CameraLens = CameraLens.BACK,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED,
    val cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val targetResolution: Pair<Int, Int>? = null,
    val directory: Directory = Directory.PICTURES,
    val mirrorFrontCamera: Boolean = false,
)
