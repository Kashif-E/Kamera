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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.kashif.cameraK.compose.rememberCameraKState
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
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
    recording: RecordingFeature,
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
    // current selection whenever the controller is (re)created.
    var cameraDeviceType by remember { mutableStateOf(CameraDeviceType.WIDE_ANGLE) }

    val cameraState by rememberCameraKState(
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
        ),
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(analyzer.plugin)
            stateHolder.attachPlugin(imageSaver.plugin)
            stateHolder.attachPlugin(qrScanner.plugin)
            stateHolder.attachPlugin(ocr.plugin)
            stateHolder.attachPlugin(recording.plugin)
        },
    )

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
                        error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) { state ->
        CameraScreen(
            cameraState = state,
            imageSaver = imageSaver,
            qrScanner = qrScanner,
            ocr = ocr,
            analyzer = analyzer,
            recording = recording,
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
    cameraState: CameraKState.Ready,
    imageSaver: ImageSaverFeature,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    analyzer: AnalyzerFeature,
    recording: RecordingFeature,
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
    val cameraController = cameraState.controller
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Re-apply the selected camera device type whenever the controller is (re)created — config
    // re-inits (aspect ratio, etc.) start from the default, so this preserves the user's choice.
    LaunchedEffect(cameraController) {
        cameraController.setPreferredCameraDeviceType(cameraDeviceType)
    }

    var cameraMode by remember { mutableStateOf(CameraMode.Photo) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationMs by remember { mutableStateOf(0L) }

    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var torchMode by remember { mutableStateOf(TorchMode.OFF) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    var isQRScanningEnabled by remember { mutableStateOf(true) }
    var isOCREnabled by remember { mutableStateOf(true) }
    var isDetectionEnabled by remember { mutableStateOf(true) }

    var showSettings by remember { mutableStateOf(false) }
    var deviceOrientation by remember { mutableStateOf(DeviceOrientation.PORTRAIT) }
    var lockedOrientation by remember { mutableStateOf<DeviceOrientation?>(null) }

    LaunchedEffect(cameraController) {
        maxZoom = cameraController.getMaxZoom()
        deviceOrientation = cameraController.getDeviceOrientation()
        cameraController.setOnOrientationChangedListener { orientation ->
            deviceOrientation = orientation
        }
    }

    RecordingEffects(
        recording = recording,
        isRecording = isRecording,
        onRecordingChange = { isRecording = it },
        onDurationChange = { recordingDurationMs = it },
    )

    QrScanningEffect(qrScanner, isQRScanningEnabled)

    OcrRecognitionEffect(ocr, isOCREnabled)

    AnalyzerEffect(analyzer, isDetectionEnabled)

    fun setCameraZoom(newLevel: Float) {
        cameraController.setZoom(newLevel)
        zoomLevel = cameraController.getZoom()
    }

    ObjectDetectionEffect(latestFrame, scope)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) {
                        setCameraZoom(zoomLevel * zoomChange)
                    }
                }
            },
    ) {
        TopBar(
            modifier = Modifier.align(Alignment.TopCenter),
            flashMode = flashMode,
            torchMode = torchMode,
            aspectRatio = aspectRatio,
            isRecording = isRecording,
            recordingDurationMs = recordingDurationMs,
            onFlashToggle = {
                cameraController.toggleFlashMode()
                flashMode = cameraController.getFlashMode() ?: FlashMode.OFF
            },
            onTorchToggle = {
                cameraController.toggleTorchMode()
                torchMode = cameraController.getTorchMode() ?: TorchMode.OFF
            },
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
            if (maxZoom > 1f) {
                ZoomChips(
                    zoomLevel = zoomLevel,
                    maxZoom = maxZoom,
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
                        onClick = {
                            cameraController.toggleCameraLens()
                            maxZoom = cameraController.getMaxZoom()
                            zoomLevel = 1f
                        },
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
                        onVideoToggle = { recording.toggleRecording(isRecording) },
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
                        cameraController.setPreferredCameraDeviceType(it) // live re-bind
                    }
                },
                onQRScanningToggle = { isQRScanningEnabled = it },
                onOCRToggle = { isOCREnabled = it },
                onOrientationLockChange = { orientation ->
                    lockedOrientation = orientation
                    cameraController.setTargetOrientation(orientation)
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
