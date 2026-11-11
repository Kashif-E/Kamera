package org.company.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.CameraPreviewScope
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
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.StatusMessage
import org.company.app.designsystem.ThirdsGrid
import org.company.app.designsystem.zoomStops

/** What the Sample lets the user change outside the camera's configuration, hoisted to [App]. */
internal class SampleOptions(
    val saveToGallery: Boolean,
    val onSaveToGalleryChange: (Boolean) -> Unit,
    val ocrAccuracy: OcrAccuracy,
    val onOcrAccuracyChange: (OcrAccuracy) -> Unit,
)

@Composable
internal fun CameraContent(
    analyzer: AnalyzerFeature,
    imageSaver: ImageSaverFeature,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    snackbarHostState: SnackbarHostState,
    options: SampleOptions,
) {
    val qrCodes = rememberQrCodes(qrScanner)
    val recognizedText = rememberRecognizedText(ocr)
    LaunchedEffect(imageSaver) {
        imageSaver.notices.collect { snackbarHostState.showSnackbar(it) }
    }

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
    CameraScreen(
        holder = holder,
        qrScanner = qrScanner,
        ocr = ocr,
        analyzer = analyzer,
        snackbarHostState = snackbarHostState,
        qrCodes = qrCodes,
        recognizedText = recognizedText,
        settings = settings,
        onSettingsChange = { settings = it },
        options = options,
    )
}

/**
 * [CameraKScreen] with the Sample's starting and error screens, and [content] over the preview. A tap
 * on the preview focuses there and shows the focus reticle, with the exposure slider beside it on a
 * camera that has exposure compensation.
 */
@Composable
internal fun SampleCameraKScreen(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    content: @Composable CameraPreviewScope.() -> Unit,
) {
    val focusTaps = remember(holder) { FocusTapRecord() }
    CameraKScreen(
        holder = holder,
        modifier = modifier,
        focusIndicator = { SampleFocusIndicator(it, focusTaps) },
        loading = {
            StatusMessage(text = "Initializing Camera...", modifier = Modifier.fillMaxSize().wrapContentSize())
        },
        error = { error, _ ->
            StatusMessage(
                text = error.displayText(),
                modifier = Modifier.fillMaxSize().wrapContentSize(),
                icon = Lucide.CircleAlert,
                iconDescription = "Error",
            )
        },
        content = {
            content()
            ExposureControl(holder, focusTaps)
        },
    )
}

/** The camera's modes, as the mode switcher names them. */
internal enum class CameraMode(val label: String) {
    Photo("PHOTO"),
    Video("VIDEO"),
    Qr("QR"),
    Text("TEXT"),
    Analyze("ANALYZE"),
}

@Composable
internal fun CameraScreen(
    holder: CameraKStateHolder,
    qrScanner: QrScannerFeature,
    ocr: OcrFeature,
    analyzer: AnalyzerFeature,
    snackbarHostState: SnackbarHostState,
    qrCodes: List<ScannedCode>,
    recognizedText: String?,
    // Config-level settings hoisted to the caller (changing one re-initializes the camera).
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    options: SampleOptions,
) {
    val scope = rememberCoroutineScope()
    val capture = remember(holder) { CaptureFeature(holder.asSampleCapturer()) }
    LaunchedEffect(capture) {
        capture.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    var isCapturing by remember { mutableStateOf(false) }
    val cameraState by holder.cameraState.collectAsState()
    val uiState by holder.uiState.collectAsState()
    val zoom by holder.zoom.collectAsState()
    val capabilities by holder.capabilities.collectAsState()
    val deviceOrientation by holder.deviceOrientation.collectAsState()
    val lastPhoto by capture.lastPhoto.collectAsState()
    val thumbnail by rememberThumbnail(lastPhoto)

    // Saved: a rotation keeps the holder and any recording, and the recreated screen must offer the
    // same mode, switches and lock, or a recording kept alive could no longer be stopped.
    var choices by rememberCameraScreenChoices()
    val recording = rememberRecordingFeature(holder)
    val recordingState by recording.state.collectAsState()
    val isRecording = recordingState.isLive()
    val mode = choices.shownMode(isRecording)
    LaunchedEffect(recording) {
        recording.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    val shownDurationMs = rememberShownRecordingDurationMs(holder, recordingState)

    var showSettings by remember { mutableStateOf(false) }

    // Once the camera is Ready: a new holder (a configuration change) starts from the default device
    // type, and one built after process death has no orientation lock. A holder kept across a rotation
    // has both, so nothing re-binds.
    val latestDeviceType by rememberUpdatedState(settings.cameraDeviceType)
    val latestLock by rememberUpdatedState(choices.lockedOrientation)
    LaunchedEffect(holder) {
        holder.cameraState.first { it is CameraKState.Ready }
        val current = holder.uiState.value
        if (current.cameraDeviceType != latestDeviceType) {
            holder.selectCamera(current.cameraLens ?: CameraLens.BACK, latestDeviceType)
        }
        if (holder.uiState.value.targetOrientation != latestLock) holder.setTargetOrientation(latestLock)
    }

    ResetFocusOnSwitch(holder, mode)

    // The QR and Text modes always scan; Photo and Video only with their switches on.
    QrScanningEffect(qrScanner, choices.qrScanning || mode == CameraMode.Qr)
    OcrRecognitionEffect(ocr, choices.ocr || mode == CameraMode.Text)

    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val notify: (String) -> Unit = { scope.launch { snackbarHostState.showSnackbar(it) } }
    val copy: (String) -> Unit = {
        clipboard.setText(AnnotatedString(it))
        notify("Copied")
    }
    val open: (String) -> Unit = {
        try {
            uriHandler.openUri(it)
        } catch (e: IllegalArgumentException) {
            CameraKLogger.w(TAG, "Opening a scanned link failed", e)
            notify("Could not open the link")
        }
    }
    val toPhoto = { choices = choices.copy(mode = CameraMode.Photo) }

    // Latest wins in the holder, and the engine clamps into the camera's range.
    fun setCameraZoom(newLevel: Float) {
        scope.launch { holder.setZoom(newLevel) }
    }

    val cameraLive = cameraState is CameraKState.Ready || cameraState is CameraKState.Interrupted
    val topBar: @Composable () -> Unit = {
        when (mode) {
            CameraMode.Photo, CameraMode.Video -> CaptureTopBar(
                flashMode = uiState.flashMode ?: FlashMode.OFF,
                torchMode = uiState.torchMode ?: TorchMode.OFF,
                aspectRatio = settings.aspectRatio,
                isRecording = isRecording,
                recordingDurationMs = shownDurationMs,
                onFlashToggle = { scope.launch { holder.cycleFlashMode() } },
                onTorchToggle = { scope.launch { holder.toggleTorch() } },
                onAspectRatioCycle = {
                    if (!isRecording) {
                        onSettingsChange(settings.copy(aspectRatio = settings.aspectRatio.nextInSample()))
                    }
                },
                onOpenSettings = { showSettings = true },
            )
            CameraMode.Qr -> ToolTopBar(
                title = "QR Scanner",
                onBack = toPhoto,
                trailing = listOf({
                    TorchButton(
                        torchMode = uiState.torchMode ?: TorchMode.OFF,
                        onClick = { scope.launch { holder.toggleTorch() } },
                    )
                }),
            )
            CameraMode.Text -> ToolTopBar(
                title = "Text",
                onBack = toPhoto,
                trailing = listOf({ OcrAccuracyToggle(options.ocrAccuracy, options.onOcrAccuracyChange) }),
            )
            CameraMode.Analyze -> ToolTopBar(title = "Analyze", onBack = toPhoto)
        }
    }
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier = modifier
                .background(SampleTheme.colors.previewPlaceholder)
                .testTag(PREVIEW_TAG)
                .pointerInput(holder) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) setCameraZoom(zoom.ratio * zoomChange)
                    }
                },
        ) {
            SampleCameraKScreen(holder, Modifier.fillMaxSize()) {
                when (mode) {
                    CameraMode.Photo -> ThirdsGrid()
                    CameraMode.Qr -> QrOverlay(qrScanner)
                    else -> Unit
                }
            }
            if (!isRecording && (mode == CameraMode.Photo || mode == CameraMode.Video)) {
                DetectionChips(
                    latestCode = qrCodes.firstOrNull().takeIf { choices.qrScanning },
                    textLine = recognizedText?.lineSequence()?.firstOrNull { it.isNotBlank() }
                        ?.takeIf { choices.ocr },
                    onOpenQr = { choices = choices.copy(mode = CameraMode.Qr) },
                    onOpenText = { choices = choices.copy(mode = CameraMode.Text) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        when (mode) {
            CameraMode.Photo, CameraMode.Video -> CaptureControls(
                state = CaptureControlsState(
                    mode = mode,
                    cameraLive = cameraLive,
                    isRecording = isRecording,
                    isPaused = recordingState is RecordingState.Paused,
                    canPause = capabilities.supportsPauseResume,
                    isCapturing = isCapturing,
                    zoomStops = zoomStops(zoom.minRatio, zoom.maxRatio),
                    zoom = zoom.ratio,
                    thumbnail = thumbnail,
                    rotationDegrees = deviceOrientation.compensationDegrees,
                ),
                onZoom = ::setCameraZoom,
                onModeChange = { choices = choices.copy(mode = it) },
                onShutter = {
                    if (mode == CameraMode.Photo) {
                        if (!isCapturing) {
                            isCapturing = true
                            scope.launch {
                                capture.takePhoto()
                                isCapturing = false
                            }
                        }
                    } else {
                        scope.launch { recording.toggleRecording() }
                    }
                },
                onPauseToggle = { scope.launch { holder.togglePause()?.let(notify) } },
                onFlip = { scope.launch { holder.toggleLens() } },
                modifier = modifier,
            )
            CameraMode.Qr -> QrResultSheet(codes = qrCodes, onCopy = copy, onOpen = open, modifier = modifier)
            CameraMode.Text -> TextResultSheet(text = recognizedText, onCopy = copy, modifier = modifier)
            CameraMode.Analyze -> AnalyzerSheet(analyzer = analyzer, modifier = modifier)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(SampleTheme.colors.background)) {
        if (maxWidth > maxHeight) {
            // Held sideways the screen is as short as it was narrow: the controls go beside the
            // preview, which keeps the height but the top bar's. The capture controls sit in the
            // middle; a result sheet rises from the bottom, as it does upright.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    topBar()
                    preview(Modifier.weight(1f).fillMaxWidth())
                }
                Box(
                    modifier = Modifier.width(SampleTheme.sizes.sidePanel).fillMaxHeight(),
                    contentAlignment = if (mode == CameraMode.Photo || mode == CameraMode.Video) {
                        Alignment.Center
                    } else {
                        Alignment.BottomCenter
                    },
                ) {
                    Box(Modifier.verticalScroll(rememberScrollState())) { controls(Modifier.fillMaxWidth()) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar()
                preview(Modifier.weight(1f).fillMaxWidth())
                // The preview keeps one size in every mode; a sheet taller than this overlaps its bottom.
                Spacer(Modifier.height(SampleTheme.sizes.controlsArea))
            }
            controls(
                Modifier.align(Alignment.BottomCenter).widthIn(max = SampleTheme.sizes.contentMaxWidth).fillMaxWidth(),
            )
        }

        if (showSettings) {
            SettingsSheet(
                settings = settings,
                // Config-level changes re-initialize the camera, so ignore them mid-recording
                // (tearing the controller down under an active recording would corrupt the clip).
                onSettingsChange = { changed ->
                    if (!isRecording) {
                        if (changed.cameraDeviceType != settings.cameraDeviceType) {
                            // Live re-bind, on the camera facing the same way.
                            scope.launch {
                                holder.selectCamera(uiState.cameraLens ?: CameraLens.BACK, changed.cameraDeviceType)
                            }
                        }
                        onSettingsChange(changed)
                    }
                },
                choices = choices,
                onChoicesChange = { changed ->
                    if (changed.lockedOrientation != choices.lockedOrientation) {
                        scope.launch { holder.setTargetOrientation(changed.lockedOrientation) }
                    }
                    choices = changed
                },
                saveToGallery = options.saveToGallery,
                onSaveToGalleryChange = options.onSaveToGalleryChange,
                deviceOrientation = deviceOrientation,
                configLocked = isRecording,
                onDismiss = { showSettings = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val TAG = "Sample"

/** The preview's test tag. */
internal const val PREVIEW_TAG = "preview"

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
    is CameraKError.InvalidArgument -> "That value is out of range"
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

// RATIO_9_16 is deprecated as the same as RATIO_16_9, so the Sample does not offer it.
internal val sampleAspectRatios = listOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_1_1)

/** The ratio the aspect-ratio button selects after this one. */
internal fun AspectRatio.nextInSample(): AspectRatio =
    sampleAspectRatios.firstOrNull { it.ordinal > ordinal } ?: sampleAspectRatios.first()

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
