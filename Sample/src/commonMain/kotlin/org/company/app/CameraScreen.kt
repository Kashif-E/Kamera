package org.company.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.rememberCameraKStateHolder
import com.kashif.cameraK.controls.cycleFlashMode
import com.kashif.cameraK.controls.toggleLens
import com.kashif.cameraK.controls.toggleTorch
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@Composable
internal fun CameraContent(
    analyzer: AnalyzerFeature,
    imageSaver: ImageSaverFeature,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    snackbarHostState: SnackbarHostState,
) {
    val qrCodes = rememberQrCodes(qrScanner)
    val recognizedText = rememberRecognizedText(ocr)

    // Config-level settings with no runtime setter: changing one rebuilds the CameraConfiguration
    // and re-initializes the camera. Saved, so a rotation rebuilds an equal configuration and the
    // retained holder, with any recording, carries on. The device type is applied with the runtime
    // setter (a live re-bind), so it is not part of the configuration; CameraScreen applies it.
    var settings by rememberSaveable(stateSaver = CameraSettingsSaver) { mutableStateOf(CameraSettings()) }

    // Present at creation, so the holder binds frame analysis in the first bind. A recreated screen
    // remembers new plugin instances, and the holder swaps them for the old ones.
    val plugins = remember(analyzer, imageSaver, qrScanner, ocr) {
        listOf(analyzer.plugin, imageSaver.plugin, qrScanner.plugin, ocr.plugin)
    }
    val holder = rememberCameraKStateHolder(
        config = CameraConfiguration {
            cameraLens = CameraLens.BACK
            flashMode = FlashMode.OFF
            imageFormat = settings.imageFormat
            torchMode = TorchMode.OFF
            qualityPrioritization = settings.qualityPrioritization
            cameraDeviceType = CameraDeviceType.WIDE_ANGLE
            aspectRatio = settings.aspectRatio
            targetResolution = settings.resolution?.let { (width, height) -> Resolution(width, height) }
        },
        plugins = plugins,
    )
    SampleCameraKScreen(holder) {
        CameraScreen(
            holder = holder,
            imageSaver = imageSaver,
            qrScanner = qrScanner,
            ocr = ocr,
            analyzer = analyzer,
            snackbarHostState = snackbarHostState,
            qrCodes = qrCodes,
            recognizedText = recognizedText,
            aspectRatio = settings.aspectRatio,
            onAspectRatioChange = { settings = settings.copy(aspectRatio = it) },
            resolution = settings.resolution,
            onResolutionChange = { settings = settings.copy(resolution = it) },
            imageFormat = settings.imageFormat,
            onImageFormatChange = { settings = settings.copy(imageFormat = it) },
            qualityPrioritization = settings.qualityPrioritization,
            onQualityPrioritizationChange = { settings = settings.copy(qualityPrioritization = it) },
            cameraDeviceType = settings.cameraDeviceType,
            onCameraDeviceTypeChange = { settings = settings.copy(cameraDeviceType = it) },
        )
    }
}

/** [CameraKScreen] with the Sample's starting and error screens, and [content] over the preview. */
@Composable
internal fun SampleCameraKScreen(holder: CameraKStateHolder, content: @Composable () -> Unit) {
    CameraKScreen(
        holder = holder,
        loading = {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Initializing Camera...",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                )
            }
        },
        error = { error, _ ->
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = "Error",
                        tint = Red,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        error.displayText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) {
        content()
    }
}

internal enum class CameraMode { Photo, Video }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(
    holder: CameraKStateHolder,
    imageSaver: ImageSaverFeature,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    analyzer: AnalyzerFeature,
    snackbarHostState: SnackbarHostState,
    qrCodes: List<String>,
    recognizedText: String?,
    // Config-level settings hoisted to the caller (changing one re-initializes the camera).
    aspectRatio: AspectRatio,
    onAspectRatioChange: (AspectRatio) -> Unit,
    resolution: Pair<Int, Int>?,
    onResolutionChange: (Pair<Int, Int>?) -> Unit,
    imageFormat: ImageFormat,
    onImageFormatChange: (ImageFormat) -> Unit,
    qualityPrioritization: QualityPrioritization,
    onQualityPrioritizationChange: (QualityPrioritization) -> Unit,
    cameraDeviceType: CameraDeviceType,
    onCameraDeviceTypeChange: (CameraDeviceType) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val capture = remember(holder) { CaptureFeature(holder.asSampleCapturer()) }
    LaunchedEffect(capture) {
        capture.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    var isCapturing by remember { mutableStateOf(false) }
    val uiState by holder.uiState.collectAsState()
    val zoom by holder.zoom.collectAsState()
    val deviceOrientation by holder.deviceOrientation.collectAsState()

    // Re-apply the selected camera device type when a new holder (a configuration change) starts
    // from the default. A holder kept across a rotation already has it, so nothing re-binds.
    LaunchedEffect(holder) {
        if (holder.uiState.value.cameraDeviceType != cameraDeviceType) {
            holder.selectCamera(uiState.cameraLens ?: CameraLens.BACK, cameraDeviceType)
        }
    }

    // Saved: a rotation keeps the holder and any recording, and the recreated screen must offer the
    // same mode, switches and lock, or a recording kept alive could no longer be stopped.
    var choices by rememberCameraScreenChoices()
    val recording = rememberRecordingFeature(holder)
    val recordingState by recording.state.collectAsState()
    val isRecording = recordingState.isLive()
    val cameraMode = choices.shownMode(isRecording)
    val isQRScanningEnabled = choices.qrScanning
    val isOCREnabled = choices.ocr
    val lockedOrientation = choices.lockedOrientation
    LaunchedEffect(recording) {
        recording.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    val shownDurationMs = rememberShownRecordingDurationMs(holder, recordingState)

    var showSettings by remember { mutableStateOf(false) }

    // A holder kept across a rotation still has the lock; one built after process death does not.
    LaunchedEffect(holder) {
        if (holder.uiState.value.targetOrientation != lockedOrientation) {
            holder.setTargetOrientation(lockedOrientation)
        }
    }

    QrScanningEffect(qrScanner, isQRScanningEnabled)

    OcrRecognitionEffect(ocr, isOCREnabled)

    // Latest wins in the holder, and the engine clamps into the camera's range.
    fun setCameraZoom(newLevel: Float) {
        scope.launch { holder.setZoom(newLevel) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(holder) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) {
                        setCameraZoom(zoom.ratio * zoomChange)
                    }
                }
            },
    ) {
        TopBar(
            modifier = Modifier.align(Alignment.TopCenter),
            flashMode = uiState.flashMode ?: FlashMode.OFF,
            torchMode = uiState.torchMode ?: TorchMode.OFF,
            aspectRatio = aspectRatio,
            isRecording = isRecording,
            recordingDurationMs = shownDurationMs,
            onFlashToggle = { scope.launch { holder.cycleFlashMode() } },
            onTorchToggle = { scope.launch { holder.toggleTorch() } },
            onAspectRatioCycle = {
                if (!isRecording) {
                    val entries = AspectRatio.entries
                    val next = entries[(entries.indexOf(aspectRatio) + 1) % entries.size]
                    onAspectRatioChange(next)
                }
            },
        )

        if (isOCREnabled && recognizedText != null) {
            OcrBanner(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 220.dp),
                text = recognizedText,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(bottom = 16.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Zoom chips
            if (zoom.maxRatio > 1f) {
                ZoomChips(
                    zoomLevel = zoom.ratio,
                    maxZoom = zoom.maxRatio,
                    onZoomChange = { setCameraZoom(it) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (isQRScanningEnabled && qrCodes.isNotEmpty()) {
                QrChipRow(qrCodes = qrCodes)
                Spacer(modifier = Modifier.height(10.dp))
            }
            // Mode switcher
            ModeSwitcher(
                currentMode = cameraMode,
                onModeChange = { choices = choices.copy(mode = it) },
                enabled = !isRecording,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val orientationRotation = deviceOrientation.compensationDegrees

                Box(modifier = Modifier.graphicsLayer { rotationZ = orientationRotation }) {
                    CameraFlipButton(
                        onClick = { scope.launch { holder.toggleLens() } },
                        enabled = !isRecording,
                    )
                }

                Box(modifier = Modifier.graphicsLayer { rotationZ = orientationRotation }) {
                    ShutterButton(
                        mode = cameraMode,
                        isRecording = isRecording,
                        isCapturing = isCapturing,
                        onPhotoCapture = {
                            if (!isCapturing) {
                                isCapturing = true
                                scope.launch {
                                    capture.takePhoto()
                                    isCapturing = false
                                }
                            }
                        },
                        onVideoToggle = {
                            scope.launch { recording.toggleRecording() }
                        },
                    )
                }

                Box(modifier = Modifier.graphicsLayer { rotationZ = orientationRotation }) {
                    SettingsButton(
                        isOpen = showSettings,
                        onClick = { showSettings = !showSettings },
                    )
                }
            }
        }

        if (showSettings) {
            SettingsPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                resolution = resolution,
                imageFormat = imageFormat,
                qualityPrioritization = qualityPrioritization,
                cameraDeviceType = cameraDeviceType,
                isQRScanningEnabled = isQRScanningEnabled,
                isOCREnabled = isOCREnabled,
                lockedOrientation = lockedOrientation,
                deviceOrientation = deviceOrientation,
                // Config-level changes re-initialize the camera, so ignore them mid-recording
                // (tearing the controller down under an active recording would corrupt the clip).
                onResolutionChange = { if (!isRecording) onResolutionChange(it) },
                onImageFormatChange = { if (!isRecording) onImageFormatChange(it) },
                onQualityPrioritizationChange = { if (!isRecording) onQualityPrioritizationChange(it) },
                onCameraDeviceTypeChange = {
                    if (!isRecording) {
                        onCameraDeviceTypeChange(it)
                        // Live re-bind, on the camera facing the same way.
                        scope.launch { holder.selectCamera(uiState.cameraLens ?: CameraLens.BACK, it) }
                    }
                },
                onQRScanningToggle = { choices = choices.copy(qrScanning = it) },
                onOCRToggle = { choices = choices.copy(ocr = it) },
                onOrientationLockChange = { orientation ->
                    choices = choices.copy(lockedOrientation = orientation)
                    scope.launch { holder.setTargetOrientation(orientation) }
                },
                onDismiss = { showSettings = false },
            )
        }
    }
}

// A fixed text per kind of error: an error's cause may carry a platform message with a file path in it.
private fun CameraKError.displayText(): String = when (this) {
    is CameraKError.Permission -> if (needsSettings) "Allow camera access in Settings" else "Camera access is needed"
    is CameraKError.CameraInUse -> "Another app is using the camera"
    is CameraKError.CameraDisabled -> "The camera is turned off on this device"
    is CameraKError.NoCamera -> "No camera found"
    is CameraKError.Disconnected -> "The camera was disconnected"
    is CameraKError.Storage -> "Could not save to storage"
    is CameraKError.Unsupported -> "This camera setting is not supported"
    is CameraKError.NativeLibraryMissing -> "A camera library is missing"
    is CameraKError.Hardware -> "The camera reported a hardware error"
    is CameraKError.Timeout -> "The camera took too long to start"
    is CameraKError.Busy -> "The camera is busy"
    is CameraKError.InvalidState -> "The camera cannot do that now"
    CameraKError.NotReady -> "The camera is not ready"
    CameraKError.Closed -> "The camera is closed"
    is CameraKError.Internal -> "Something went wrong with the camera"
}

/** The configuration settings the Sample lets the user change; each change builds a new configuration. */
internal data class CameraSettings(
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val resolution: Pair<Int, Int>? = null,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val qualityPrioritization: QualityPrioritization = QualityPrioritization.BALANCED,
    val cameraDeviceType: CameraDeviceType = CameraDeviceType.WIDE_ANGLE,
)

/** Saves each setting as its name, and the resolution as two numbers or none: what an Android Bundle holds. */
internal val CameraSettingsSaver: Saver<CameraSettings, Any> = Saver(
    save = { settings ->
        listOf(
            settings.aspectRatio.name,
            settings.resolution?.let { listOf(it.first, it.second) },
            settings.imageFormat.name,
            settings.qualityPrioritization.name,
            settings.cameraDeviceType.name,
        )
    },
    restore = ::restoreCameraSettings,
)

// Null for anything this saver did not write; the screen then starts from the defaults.
private fun restoreCameraSettings(saved: Any): CameraSettings? {
    val values = saved as? List<*> ?: return null
    if (values.size != SETTINGS_FIELDS) return null
    val resolution = when (val size = values[1]) {
        null -> null
        is List<*> -> (size.getOrNull(0) as? Int ?: return null) to (size.getOrNull(1) as? Int ?: return null)
        else -> return null
    }
    return CameraSettings(
        aspectRatio = AspectRatio.entries.firstOrNull { it.name == values[0] } ?: return null,
        resolution = resolution,
        imageFormat = ImageFormat.entries.firstOrNull { it.name == values[2] } ?: return null,
        qualityPrioritization = QualityPrioritization.entries.firstOrNull { it.name == values[3] } ?: return null,
        cameraDeviceType = CameraDeviceType.entries.firstOrNull { it.name == values[4] } ?: return null,
    )
}

private const val SETTINGS_FIELDS = 5

/**
 * The screen's own choices, which no holder keeps: the mode, the QR and OCR switches and the
 * orientation lock. Saved, so a rotation brings them back with the holder it keeps.
 */
internal data class CameraScreenChoices(
    val mode: CameraMode = CameraMode.Photo,
    val qrScanning: Boolean = true,
    val ocr: Boolean = true,
    val lockedOrientation: DeviceOrientation? = null,
)

/** Saves the mode and the lock as names (no lock as none), and the switches as booleans: what a Bundle holds. */
internal val CameraScreenChoicesSaver: Saver<CameraScreenChoices, Any> = Saver(
    save = { choices -> listOf(choices.mode.name, choices.qrScanning, choices.ocr, choices.lockedOrientation?.name) },
    restore = ::restoreCameraScreenChoices,
)

// Null for anything this saver did not write; the screen then starts from the defaults.
private fun restoreCameraScreenChoices(saved: Any): CameraScreenChoices? {
    val values = saved as? List<*> ?: return null
    if (values.size != CHOICES_FIELDS) return null
    val locked = values[3]?.let { name -> DeviceOrientation.entries.firstOrNull { it.name == name } ?: return null }
    return CameraScreenChoices(
        mode = CameraMode.entries.firstOrNull { it.name == values[0] } ?: return null,
        qrScanning = values[1] as? Boolean ?: return null,
        ocr = values[2] as? Boolean ?: return null,
        lockedOrientation = locked,
    )
}

private const val CHOICES_FIELDS = 4

/** The screen's choices, saved, so a recreated screen starts from them. */
@Composable
internal fun rememberCameraScreenChoices(): MutableState<CameraScreenChoices> =
    rememberSaveable(stateSaver = CameraScreenChoicesSaver) { mutableStateOf(CameraScreenChoices()) }

/**
 * The mode the shutter and the mode switcher show: Video while a recording is live, whatever was
 * selected, so the shutter can always stop the recording.
 */
internal fun CameraScreenChoices.shownMode(recordingLive: Boolean): CameraMode =
    if (recordingLive) CameraMode.Video else mode

/** The holder's capture, as the Sample's photo button uses it. */
internal interface SampleCapturer {
    suspend fun capture(): CameraKResult<CapturedImage>
}

internal fun CameraKStateHolder.asSampleCapturer(): SampleCapturer {
    val holder = this
    return object : SampleCapturer {
        override suspend fun capture(): CameraKResult<CapturedImage> = holder.capture()
    }
}

/**
 * The Sample's photo action, on the holder's capture: every photo reaches the holder's captures,
 * where the image saver picks it up and puts it in the gallery.
 */
internal class CaptureFeature(private val capturer: SampleCapturer) {
    private val _lastPhoto = MutableStateFlow<CapturedImage?>(null)

    /** The last photo taken. */
    val lastPhoto: StateFlow<CapturedImage?> = _lastPhoto.asStateFlow()

    private val _notices = Channel<String>(Channel.BUFFERED)

    /** One line per photo taken or failed, for the screen to show; the save is the image saver's. */
    val notices: Flow<String> = _notices.receiveAsFlow()

    suspend fun takePhoto() {
        when (val result = capturer.capture()) {
            is CameraKResult.Success -> {
                _lastPhoto.value = result.value
                // The image saver saves it from the holder's captures; this is only the capture.
                _notices.trySend("Photo taken")
            }
            is CameraKResult.Failure -> {
                CameraKLogger.w(TAG, "Taking a photo failed: ${result.error::class.simpleName}", result.error.cause)
                _notices.trySend("Photo failed: ${result.error.displayText()}")
            }
        }
    }

    private companion object {
        const val TAG = "Sample"
    }
}
