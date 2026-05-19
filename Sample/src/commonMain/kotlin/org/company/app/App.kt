package org.company.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.AlignVerticalSpaceAround
import com.composables.icons.lucide.Aperture
import com.composables.icons.lucide.Focus
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Ratio
import com.composables.icons.lucide.ScanLine
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SwitchCamera
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.kashif.analyzerPlugin.AnalyzerPlugin
import com.kashif.analyzerPlugin.rememberAnalyzerPlugin
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
import com.kashif.cameraK.permissions.Permissions
import com.kashif.cameraK.permissions.providePermissions
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import com.kashif.imagesaverplugin.ImageSaverConfig
import com.kashif.imagesaverplugin.ImageSaverPlugin
import com.kashif.imagesaverplugin.rememberImageSaverPlugin
import com.kashif.ocrPlugin.OcrPlugin
import com.kashif.ocrPlugin.rememberOcrPlugin
import com.kashif.qrscannerplugin.QRScannerPlugin
import com.kashif.qrscannerplugin.rememberQRScannerPlugin
import com.kashif.videorecorderplugin.VideoRecorderPlugin
import com.kashif.videorecorderplugin.rememberVideoRecorderPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.company.app.theme.AppTheme
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.uuid.ExperimentalUuidApi

private val Red = Color(0xFFEA4335)
private val Surface = Color(0xFF1A1A1A)
private val ChipBg = Color(0xFF2D2D2D)
private val ChipBgSelected = Color(0xFF4A4A4A)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFFB3B3B3)

@Composable
fun App() = AppTheme {
    val permissions: Permissions = providePermissions()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val cameraPermissionState = remember { mutableStateOf(permissions.hasCameraPermission()) }
        val storagePermissionState = remember { mutableStateOf(permissions.hasStoragePermission()) }

        val imageSaverPlugin = rememberImageSaverPlugin(
            config = ImageSaverConfig(
                isAutoSave = true,
                prefix = "CameraK",
                directory = Directory.PICTURES,
                customFolderName = "CameraK",
            ),
        )
        val analyzerPlugin = rememberAnalyzerPlugin()
        val qrScannerPlugin = rememberQRScannerPlugin()
        val ocrPlugin = rememberOcrPlugin()
        val videoRecorderPlugin = rememberVideoRecorderPlugin(
            config = VideoConfiguration(
                quality = VideoQuality.FHD,
                enableAudio = true,
                maxDurationMs = 300_000L,
            ),
        )

        PermissionsHandler(
            permissions = permissions,
            cameraPermissionState = cameraPermissionState,
            storagePermissionState = storagePermissionState,
        )

        if (cameraPermissionState.value && storagePermissionState.value) {
            CameraContent(
                analyzerPlugin = analyzerPlugin,
                imageSaverPlugin = imageSaverPlugin,
                qrScannerPlugin = qrScannerPlugin,
                ocrPlugin = ocrPlugin,
                videoRecorderPlugin = videoRecorderPlugin,
            )
        }
    }
}

@Composable
private fun PermissionsHandler(
    permissions: Permissions,
    cameraPermissionState: MutableState<Boolean>,
    storagePermissionState: MutableState<Boolean>,
) {
    if (!cameraPermissionState.value) {
        permissions.RequestCameraPermission(
            onGranted = { cameraPermissionState.value = true },
            onDenied = { println("Camera Permission Denied") },
        )
    }

    if (!storagePermissionState.value) {
        permissions.RequestStoragePermission(
            onGranted = { storagePermissionState.value = true },
            onDenied = { println("Storage Permission Denied") },
        )
    }
}

@Composable
private fun CameraContent(
    analyzerPlugin: AnalyzerPlugin,
    imageSaverPlugin: ImageSaverPlugin,
    qrScannerPlugin: QRScannerPlugin,
    ocrPlugin: OcrPlugin,
    videoRecorderPlugin: VideoRecorderPlugin,
) {
    var qrCodes by remember { mutableStateOf(listOf<String>()) }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    var latestFrame by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(analyzerPlugin) {
        analyzerPlugin.getAnalyzerFlow().collect { frame ->
            latestFrame = frame
        }
    }
    LaunchedEffect(qrScannerPlugin) {
        qrScannerPlugin.getQrCodeFlow().collect { qr ->
            if (qr !in qrCodes) {
                qrCodes = (listOf(qr) + qrCodes).take(5)
            }
        }
    }

    LaunchedEffect(ocrPlugin) {
        for (text in ocrPlugin.ocrFlow) {
            recognizedText = text
        }
    }

    val cameraState by rememberCameraKState(
        config = CameraConfiguration(
            cameraLens = CameraLens.BACK,
            flashMode = FlashMode.OFF,
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            torchMode = TorchMode.OFF,
            qualityPrioritization = QualityPrioritization.BALANCED,
            cameraDeviceType = CameraDeviceType.WIDE_ANGLE,
            aspectRatio = AspectRatio.RATIO_4_3,
        ),
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(imageSaverPlugin)
            stateHolder.attachPlugin(qrScannerPlugin)
            stateHolder.attachPlugin(ocrPlugin)
            stateHolder.attachPlugin(videoRecorderPlugin)
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
            imageSaverPlugin = imageSaverPlugin,
            qrScannerPlugin = qrScannerPlugin,
            ocrPlugin = ocrPlugin,
            analyzerPlugin = analyzerPlugin,
            videoRecorderPlugin = videoRecorderPlugin,
            qrCodes = qrCodes,
            recognizedText = recognizedText,
            latestFrame = latestFrame
        )
    }
}

private enum class CameraMode { Photo, Video }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(
    cameraState: CameraKState.Ready,
    imageSaverPlugin: ImageSaverPlugin,
    qrScannerPlugin: QRScannerPlugin,
    ocrPlugin: OcrPlugin,
    analyzerPlugin: AnalyzerPlugin,
    videoRecorderPlugin: VideoRecorderPlugin,
    qrCodes: List<String>,
    recognizedText: String?,
    latestFrame: ByteArray?
) {
    val scope = rememberCoroutineScope()
    val cameraController = cameraState.controller
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }


    var cameraMode by remember { mutableStateOf(CameraMode.Photo) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationMs by remember { mutableStateOf(0L) }


    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var torchMode by remember { mutableStateOf(TorchMode.OFF) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var aspectRatio by remember { mutableStateOf(AspectRatio.RATIO_4_3) }
    var resolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var imageFormat by remember { mutableStateOf(ImageFormat.JPEG) }
    var qualityPrioritization by remember { mutableStateOf(QualityPrioritization.BALANCED) }
    var cameraDeviceType by remember { mutableStateOf(CameraDeviceType.WIDE_ANGLE) }


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

    LaunchedEffect(videoRecorderPlugin) {
        videoRecorderPlugin.recordingEvents.collect { event ->
            when (event) {
                is CameraKEvent.RecordingStarted -> {
                    isRecording = true
                }

                is CameraKEvent.RecordingStopped,
                is CameraKEvent.RecordingFailed,
                is CameraKEvent.RecordingMaxDurationReached,
                    -> {
                    isRecording = false
                    recordingDurationMs = 0L
                }

                else -> {}
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (true) {
                recordingDurationMs = videoRecorderPlugin.recordingDurationMs
                delay(250L)
            }
        }
    }

    LaunchedEffect(isQRScanningEnabled) {
        try {
            if (isQRScanningEnabled) {
                qrScannerPlugin.startScanning()
            } else {
                qrScannerPlugin.pauseScanning()
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(isOCREnabled) {
        try {
            if (isOCREnabled) {
                ocrPlugin.startRecognition()
            } else {
                ocrPlugin.stopRecognition()
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(isDetectionEnabled) {
        try {
            if (isDetectionEnabled) {
                analyzerPlugin.startAnalyzer()
            } else {
                analyzerPlugin.stopAnalyzer()
            }
        } catch (_: Exception) {
        }
    }

    fun setCameraZoom(newLevel: Float) {
        cameraController.setZoom(newLevel)
        zoomLevel = cameraController.getZoom()
    }

    LaunchedEffect(latestFrame) {
        latestFrame?.runTFliteModel()
    }

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
                val entries = AspectRatio.entries
                val next = entries[(entries.indexOf(aspectRatio) + 1) % entries.size]
                aspectRatio = next
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
                        onVideoToggle = {
                            if (isRecording) {
                                videoRecorderPlugin.stopRecording()
                            } else {
                                videoRecorderPlugin.startRecording()
                            }
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
                onResolutionChange = { resolution = it },
                onImageFormatChange = { imageFormat = it },
                onQualityPrioritizationChange = { qualityPrioritization = it },
                onCameraDeviceTypeChange = {
                    cameraDeviceType = it
                    cameraController.setPreferredCameraDeviceType(it)
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

@Composable
private fun TopBar(
    modifier: Modifier = Modifier,
    flashMode: FlashMode,
    torchMode: TorchMode,
    aspectRatio: AspectRatio,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onFlashToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onAspectRatioCycle: () -> Unit,
) {


    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRecording) {
            RecordingTimerHud(durationMs = recordingDurationMs)
        } else {
            TopBarIconChip(
                icon = if (flashMode != FlashMode.OFF) Lucide.Zap else Lucide.Zap,
                label = flashMode.name,
                active = flashMode != FlashMode.OFF,
                onClick = onFlashToggle,
            )

            Spacer(modifier = Modifier.width(6.dp))

            TopBarIconChip(
                icon = Lucide.Ratio,
                label = aspectRatio.name.replace("RATIO_", "").replace("_", ":"),
                active = false,
                onClick = onAspectRatioCycle,
            )

            Spacer(modifier = Modifier.weight(1f))

            TopBarIconChip(
                icon = if (torchMode != TorchMode.OFF) Lucide.Sun else Lucide.Sun,
                label = torchMode.name,
                active = torchMode != TorchMode.OFF,
                onClick = onTorchToggle,
            )
        }
    }
}

@Composable
private fun TopBarIconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (active) Color.White.copy(alpha = 0.2f) else Color.Transparent,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.Yellow else TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = if (active) Color.Yellow else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RecordingTimerHud(modifier: Modifier = Modifier, durationMs: Long) {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val timeText = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

    val infiniteTransition = rememberInfiniteTransition()
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Red.copy(alpha = dotAlpha), CircleShape),
        )
        Text(
            text = timeText,
            color = Red,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ZoomChips(modifier: Modifier = Modifier, zoomLevel: Float, maxZoom: Float, onZoomChange: (Float) -> Unit) {
    val stops = buildList {
        add(1f)
        if (maxZoom >= 2f) add(2f)
        if (maxZoom > 2f && maxZoom >= 4f) add(4f.coerceAtMost(maxZoom))
        if (maxZoom > 4f) add(maxZoom)
    }.distinct()

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEach { stop ->
                val isActive = (zoomLevel - stop).let { it > -0.15f && it < 0.15f }
                val label = if (stop == stop.toLong().toFloat()) {
                    "${stop.toInt()}x"
                } else {
                    "${(stop * 10).toInt() / 10f}x"
                }
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onZoomChange(stop) },
                    color = if (isActive) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = label,
                            color = if (isActive) Color.Yellow else TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitcher(currentMode: CameraMode, onModeChange: (CameraMode) -> Unit, enabled: Boolean = true) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraMode.entries.forEach { mode ->
            val isSelected = mode == currentMode
            val textColor by animateColorAsState(
                targetValue = if (isSelected) TextPrimary else TextSecondary,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(enabled = enabled) { onModeChange(mode) },
            ) {
                Text(
                    text = mode.name.uppercase(),
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (isSelected) 24.dp else 0.dp)
                        .background(
                            if (isSelected) TextPrimary else Color.Transparent,
                            RoundedCornerShape(1.5.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    mode: CameraMode,
    isRecording: Boolean,
    isCapturing: Boolean,
    onPhotoCapture: () -> Unit,
    onVideoToggle: () -> Unit,
) {
    val isVideoMode = mode == CameraMode.Video

    val outerRingColor by animateColorAsState(
        targetValue = if (isRecording) Red else Color.White,
    )
    val innerColor by animateColorAsState(
        targetValue = if (isVideoMode) Red else Color.White,
    )
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 24.dp else 60.dp,
    )
    val innerCornerRadius by animateDpAsState(
        targetValue = if (isRecording) 6.dp else 30.dp,
    )

    val pulseScale = if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition()
        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween<Float>(1000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        scale
    } else {
        1.0f
    }

    val pressScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.85f else 1.0f,
    )

    val combinedScale = pulseScale * pressScale

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clickable(
                enabled = !isCapturing,
                onClick = if (isVideoMode) onVideoToggle else onPhotoCapture,
            ),
    ) {
        Box(
            modifier = Modifier
                .size((72 * combinedScale).dp)
                .border(3.dp, outerRingColor, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(innerSize * combinedScale)
                .background(innerColor, RoundedCornerShape(innerCornerRadius)),
        )
    }
}

@Composable
private fun CameraFlipButton(onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(ChipBg, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            imageVector = Lucide.SwitchCamera,
            contentDescription = "Flip Camera",
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingsButton(isOpen: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(
                if (isOpen) ChipBgSelected else ChipBg,
                CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = if (isOpen) Lucide.X else Lucide.Settings,
            contentDescription = if (isOpen) "Close Settings" else "Settings",
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingsPanel(
    modifier: Modifier = Modifier,
    resolution: Pair<Int, Int>?,
    imageFormat: ImageFormat,
    qualityPrioritization: QualityPrioritization,
    cameraDeviceType: CameraDeviceType,
    isQRScanningEnabled: Boolean,
    isOCREnabled: Boolean,
    lockedOrientation: DeviceOrientation?,
    deviceOrientation: DeviceOrientation,
    onResolutionChange: (Pair<Int, Int>?) -> Unit,
    onImageFormatChange: (ImageFormat) -> Unit,
    onQualityPrioritizationChange: (QualityPrioritization) -> Unit,
    onCameraDeviceTypeChange: (CameraDeviceType) -> Unit,
    onQRScanningToggle: (Boolean) -> Unit,
    onOCRToggle: (Boolean) -> Unit,
    onOrientationLockChange: (DeviceOrientation?) -> Unit,
    onDismiss: () -> Unit,
) {
    val resolutionOptions = listOf(null, 1920 to 1080, 1280 to 720, 640 to 480)
    fun resLabel(r: Pair<Int, Int>?): String = r?.let { "${it.first}x${it.second}" } ?: "Auto"

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Resolution
            SettingRow(icon = Lucide.Focus, label = "Resolution") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resolutionOptions) { option ->
                        Chip(resLabel(option), option == resolution) { onResolutionChange(option) }
                    }
                }
            }

            // Image Format
            SettingRow(icon = Lucide.Image, label = "Image Format") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ImageFormat.entries.toList()) { format ->
                        Chip(format.name, format == imageFormat) { onImageFormatChange(format) }
                    }
                }
            }

            // Quality Prioritization
            SettingRow(icon = Lucide.Aperture, label = "Quality Priority") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(QualityPrioritization.entries.toList()) { priority ->
                        Chip(priority.name, priority == qualityPrioritization) {
                            onQualityPrioritizationChange(priority)
                        }
                    }
                }
            }

            // Camera Device Type
            SettingRow(icon = Lucide.Monitor, label = "Camera Type") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CameraDeviceType.entries.toList()) { type ->
                        Chip(
                            label = type.name.replace("_", " "),
                            selected = type == cameraDeviceType,
                            onClick = { onCameraDeviceTypeChange(type) },
                        )
                    }
                }
            }

            // Plugin toggles
            SettingToggle(Lucide.ScanLine, "QR Scanner", isQRScanningEnabled, onQRScanningToggle)
            SettingToggle(Lucide.Type, "OCR (Text Recognition)", isOCREnabled, onOCRToggle)

            // Orientation lock
            SettingRow(icon = Lucide.AlignVerticalSpaceAround, label = "Orientation Lock (${deviceOrientation.name})") {
                val options = listOf<DeviceOrientation?>(null) + DeviceOrientation.entries
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options) { option ->
                        Chip(
                            label = option?.name?.replace("_", " ") ?: "AUTO",
                            selected = option == lockedOrientation,
                            onClick = { onOrientationLockChange(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
            )
        }
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) Color.White else ChipBg,
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Color.Black else TextSecondary,
    )

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SettingToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) Color(0xFF4285F4) else TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4285F4),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ChipBg,
            ),
        )
    }
}

@Composable
private fun QrChipRow(qrCodes: List<String>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(qrCodes) { code ->
            Surface(
                color = Color(0xFF4CAF50).copy(alpha = 0.9f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Lucide.ScanLine,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (code.length > 30) code.take(30) + "..." else code,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OcrBanner(modifier: Modifier = Modifier, text: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = Color(0xFF1A237E).copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Lucide.Type,
                    contentDescription = null,
                    tint = Color(0xFF82B1FF),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Text Detected",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF82B1FF),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "Less" else "More",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = if (expanded) 10 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalResourceApi::class, ExperimentalUuidApi::class)
private suspend fun handleImageCapture(cameraController: CameraController, onImageCaptured: (ImageBitmap) -> Unit) {
    when (val result = cameraController.takePictureToFile()) {
        is ImageCaptureResult.SuccessWithFile -> {
            println("Image captured: ${result.filePath}")
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
