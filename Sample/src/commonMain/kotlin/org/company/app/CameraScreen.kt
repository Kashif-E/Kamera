package org.company.app

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.rememberCameraKStateHolder
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controls.cycleFlashMode
import com.kashif.cameraK.controls.toggleLens
import com.kashif.cameraK.controls.toggleTorch
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.uuid.ExperimentalUuidApi

@Composable
internal fun CameraContent(
    analyzer: AnalyzerFeature,
    imageSaver: ImageSaverFeature,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    snackbarHostState: SnackbarHostState,
) {
    val latestFrame = rememberLatestFrame(analyzer)
    val qrCodes = rememberQrCodes(qrScanner)
    val recognizedText = rememberRecognizedText(ocr)

    // Config-level settings with no runtime setter: changing one rebuilds the CameraConfiguration
    // and re-initializes the camera (data-class equality keeps it stable while unchanged).
    var aspectRatio by remember { mutableStateOf(AspectRatio.RATIO_4_3) }
    var resolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var imageFormat by remember { mutableStateOf(ImageFormat.JPEG) }
    var qualityPrioritization by remember { mutableStateOf(QualityPrioritization.BALANCED) }
    // Device type is applied with the runtime setter (live re-bind), so it's NOT part of the
    // re-init config; the initial value seeds the first bind and CameraScreen re-applies the
    // current selection whenever the holder is (re)created.
    var cameraDeviceType by remember { mutableStateOf(CameraDeviceType.WIDE_ANGLE) }

    val holder = rememberCameraKStateHolder(
        config = CameraConfiguration(
            cameraLens = CameraLens.BACK,
            flashMode = FlashMode.OFF,
            imageFormat = imageFormat,
            // The image saver puts each photo in the gallery; a gallery directory here would store it twice.
            directory = Directory.DOCUMENTS,
            torchMode = TorchMode.OFF,
            qualityPrioritization = qualityPrioritization,
            cameraDeviceType = CameraDeviceType.WIDE_ANGLE,
            aspectRatio = aspectRatio,
            targetResolution = resolution,
            // The frame plugins attach below, after the holder exists, so the holder cannot derive
            // the flag from them: declared here, ImageAnalysis is in the first bind.
            frameAnalysis = true,
        ),
    )
    LaunchedEffect(holder) {
        holder.attachPlugin(analyzer.plugin)
        holder.attachPlugin(imageSaver.plugin)
        holder.attachPlugin(qrScanner.plugin)
        holder.attachPlugin(ocr.plugin)
    }
    val cameraState by holder.cameraState.collectAsState()

    CameraKScreen(
        cameraState = cameraState,
        showPreview = true,
        loadingContent = {
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
        errorContent = { error ->
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
                        error.error.displayText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) { state ->
        CameraScreen(
            holder = holder,
            cameraState = state,
            imageSaver = imageSaver,
            qrScanner = qrScanner,
            ocr = ocr,
            analyzer = analyzer,
            snackbarHostState = snackbarHostState,
            qrCodes = qrCodes,
            recognizedText = recognizedText,
            latestFrame = latestFrame,
            aspectRatio = aspectRatio,
            onAspectRatioChange = { aspectRatio = it },
            resolution = resolution,
            onResolutionChange = { resolution = it },
            imageFormat = imageFormat,
            onImageFormatChange = { imageFormat = it },
            qualityPrioritization = qualityPrioritization,
            onQualityPrioritizationChange = { qualityPrioritization = it },
            cameraDeviceType = cameraDeviceType,
            onCameraDeviceTypeChange = { cameraDeviceType = it },
        )
    }
}

internal enum class CameraMode { Photo, Video }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(
    holder: CameraKStateHolder,
    cameraState: CameraKState.Ready,
    imageSaver: ImageSaverFeature,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    analyzer: AnalyzerFeature,
    snackbarHostState: SnackbarHostState,
    qrCodes: List<String>,
    recognizedText: String?,
    latestFrame: ByteArray?,
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
    // The capture button stays on the 1.x controller; recording runs on the holder.
    val cameraController = cameraState.controller
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    val uiState by holder.uiState.collectAsState()
    val zoom by holder.zoom.collectAsState()
    val deviceOrientation by holder.deviceOrientation.collectAsState()

    // Re-apply the selected camera device type whenever the holder is (re)created — config
    // re-inits (aspect ratio, etc.) start from the default, so this preserves the user's choice.
    LaunchedEffect(holder) {
        holder.selectCamera(uiState.cameraLens ?: CameraLens.BACK, cameraDeviceType)
    }

    var cameraMode by remember { mutableStateOf(CameraMode.Photo) }
    val recording = rememberRecordingFeature(holder)
    val recordingState by recording.state.collectAsState()
    val isRecording = recordingState.isLive()
    LaunchedEffect(recording) {
        recording.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    var recordingDurationMs by remember { mutableStateOf(0L) }

    var isQRScanningEnabled by remember { mutableStateOf(true) }
    var isOCREnabled by remember { mutableStateOf(true) }
    var isDetectionEnabled by remember { mutableStateOf(true) }

    var showSettings by remember { mutableStateOf(false) }
    var lockedOrientation by remember { mutableStateOf<DeviceOrientation?>(null) }

    RecordingEffects(
        recording = recording,
        isRecording = isRecording,
        onDurationChange = { recordingDurationMs = it },
    )

    QrScanningEffect(qrScanner, isQRScanningEnabled)

    OcrRecognitionEffect(ocr, isOCREnabled)

    AnalyzerEffect(analyzer, isDetectionEnabled)

    // Latest wins in the holder, and the engine clamps into the camera's range.
    fun setCameraZoom(newLevel: Float) {
        scope.launch { holder.setZoom(newLevel) }
    }

    ObjectDetectionEffect(latestFrame, scope)

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
            recordingDurationMs = recordingDurationMs,
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
                onModeChange = { cameraMode = it },
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
                                    handleImageCapture(
                                        cameraController = cameraController,
                                        onImageCaptured = { imageBitmap = it },
                                    )
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
                onQRScanningToggle = { isQRScanningEnabled = it },
                onOCRToggle = { isOCREnabled = it },
                onOrientationLockChange = { orientation ->
                    lockedOrientation = orientation
                    scope.launch { holder.setTargetOrientation(orientation) }
                },
                onDismiss = { showSettings = false },
            )
        }

        CapturedImagePreview(imageBitmap = imageBitmap) { imageBitmap = null }
    }
}

@OptIn(ExperimentalResourceApi::class, ExperimentalUuidApi::class)
private suspend fun handleImageCapture(cameraController: CameraController, onImageCaptured: (ImageBitmap) -> Unit) {
    when (val result = cameraController.takePictureToFile()) {
        is ImageCaptureResult.SuccessWithFile -> {
            println("Image captured: ${result.filePath}")
        }

        is ImageCaptureResult.SuccessWithUri -> {
            println("Image published to the gallery: ${result.uri}")
        }

        is ImageCaptureResult.Success -> {
            println("Image captured (${result.byteArray.size} bytes)")
            onImageCaptured(result.byteArray.decodeToImageBitmap())
        }

        is ImageCaptureResult.Error -> {
            println("Capture error: ${result.exception.message}")
        }
    }
}

@Composable
private fun CapturedImagePreview(imageBitmap: ImageBitmap?, onDismiss: () -> Unit) {
    imageBitmap?.let { bitmap ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.9f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Captured Image",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(ChipBg, CircleShape),
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = "Close Preview",
                        tint = TextPrimary,
                        modifier = Modifier.rotate(120f),
                    )
                }
            }
        }

        LaunchedEffect(bitmap) {
            delay(3000)
            onDismiss()
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
