# Plugins

Extend CameraK with modular plugins for QR scanning, OCR, and custom processing.

## Overview

Plugins extend camera functionality without modifying core camera code. They auto-activate when camera becomes ready and process frames in the background.

**Built-in plugins:**
- **QR Scanner** — Scan QR codes and barcodes
- **OCR** — Recognize text in camera feed
- **Image Saver** — Auto-save captured images with custom processing

## Using Plugins

Add plugins during initialization via `setupPlugins`:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(),
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(rememberQRScannerPlugin())
        stateHolder.attachPlugin(rememberOcrPlugin())
        stateHolder.attachPlugin(rememberImageSaverPlugin())
    }
)
```

Plugins activate automatically when camera reaches `Ready` state.

## QR Scanner Plugin

### Installation

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:qr_scanner_plugin:0.3")
}
```

### Usage

```kotlin
@Composable
fun QRScannerScreen() {
    val qrScannerPlugin = rememberQRScannerPlugin()
    var lastQrCode by remember { mutableStateOf<String?>(null) }

    val cameraState by rememberCameraKState(
        config = CameraConfiguration(),
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(qrScannerPlugin)
            // Collect QR code events
            stateHolder.pluginScope.launch {
                stateHolder.events.collect { event ->
                    when (event) {
                        is CameraKEvent.QRCodeScanned -> {
                            lastQrCode = event.qrCode
                        }
                        else -> {}
                    }
                }
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // Display scanned QR codes
                lastQrCode?.let { qrCode ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("QR Code Detected", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(qrCode, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            is CameraKState.Error -> Text("Camera error: ${state.message}")
            CameraKState.Initializing -> CircularProgressIndicator()
        }
    }
}
```

**Features:**
- Scans QR codes and barcodes automatically
- Results delivered via `CameraKEvent.QRCodeScanned` events
- No manual frame processing needed

## OCR Plugin

### Installation

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:ocr_plugin:0.3")
}
```

### Usage

```kotlin
@Composable
fun OCRScannerScreen() {
    val ocrPlugin = rememberOcrPlugin()
    var recognizedText by remember { mutableStateOf("") }

    val cameraState by rememberCameraKState(
        config = CameraConfiguration(),
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(ocrPlugin)
            // Collect OCR events
            stateHolder.pluginScope.launch {
                stateHolder.events.collect { event ->
                    when (event) {
                        is CameraKEvent.TextRecognized -> {
                            recognizedText = event.text
                        }
                        else -> {}
                    }
                }
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // Display recognized text
                if (recognizedText.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Recognized Text", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(recognizedText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            is CameraKState.Error -> Text("Camera error: ${state.message}")
            CameraKState.Initializing -> CircularProgressIndicator()
        }
    }
}
```

**Features:**
- Recognizes text in real-time
- Results delivered via `CameraKEvent.TextRecognized` events
- Supports multiple languages

## Image Saver Plugin

### Installation

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:image_saver_plugin:0.3")
}
```

### Usage

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(),
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(
            rememberImageSaverPlugin(
                config = ImageSaverConfig(
                    isAutoSave = true,  // Auto-save every capture
                    directory = Directory.PICTURES
                )
            )
        )
    }
)
```

**Features:**
- Auto-saves captured images
- Configurable save location
- Optional image processing before save

## Combine Multiple Plugins

Use multiple plugins simultaneously:

```kotlin
@Composable
fun MultiPluginCamera() {
    val scope = rememberCoroutineScope()
    val qrScannerPlugin = rememberQRScannerPlugin()
    val ocrPlugin = rememberOcrPlugin()
    val imageSaverPlugin = rememberImageSaverPlugin(config = ImageSaverConfig(isAutoSave = true))

    var lastQrCode by remember { mutableStateOf<String?>(null) }
    var recognizedText by remember { mutableStateOf("") }

    val cameraState by rememberCameraKState(
        config = CameraConfiguration(),
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(qrScannerPlugin)
            stateHolder.attachPlugin(ocrPlugin)
            stateHolder.attachPlugin(imageSaverPlugin)
            // Collect events from plugins
            stateHolder.pluginScope.launch {
                stateHolder.events.collect { event ->
                    when (event) {
                        is CameraKEvent.QRCodeScanned -> lastQrCode = event.qrCode
                        is CameraKEvent.TextRecognized -> recognizedText = event.text
                        else -> {}
                    }
                }
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                ) {
                    // QR codes
                    lastQrCode?.let { qrCode ->
                        Card {
                            Text("QR: $qrCode", modifier = Modifier.padding(8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // OCR text
                    if (recognizedText.isNotEmpty()) {
                        Card {
                            Text("Text: $recognizedText", modifier = Modifier.padding(8.dp))
                        }
                    }
                }

                // Capture button (auto-saved by plugin)
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            controller.takePictureToFile()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, "Capture")
                }
            }

            is CameraKState.Error -> Text("Camera error: ${state.message}")
            CameraKState.Initializing -> CircularProgressIndicator()
        }
    }
}
```

## Plugin Lifecycle

Plugins follow camera lifecycle:

1. **Attach** — Plugin added to state holder
2. **Auto-Activate** — Plugin starts when camera reaches `Ready` state
3. **Processing** — Plugin processes frames/events
4. **Cleanup** — Plugin stops when camera is disposed

**No manual activation needed** — plugins activate automatically.

## Custom Plugin Development

Create your own plugins by implementing `CameraKPlugin`:

```kotlin
class CustomPlugin : CameraKPlugin {
    override fun onAttach(stateHolder: CameraKStateHolder) {
        // Initialize plugin
        stateHolder.pluginScope.launch {
            // Wait for camera ready
            stateHolder.cameraState
                .filterIsInstance<CameraKState.Ready>()
                .first()

            // Get controller and start processing
            val controller = stateHolder.getReadyCameraController() ?: return@launch
            controller.addImageCaptureListener { imageData ->
                // Process captured images
                processImage(imageData)
            }
        }
    }

    override fun onDetach() {
        // Cleanup resources
    }

    private fun processImage(imageData: ByteArray) {
        // Custom processing logic
    }
}

// Usage
@Composable
fun rememberCustomPlugin(): CustomPlugin {
    return remember { CustomPlugin() }
}
```

## Plugin API Reference

### CameraKPlugin Interface

```kotlin
interface CameraKPlugin {
    fun onAttach(stateHolder: CameraKStateHolder)
    fun onDetach()
}
```

### CameraKStateHolder Plugin Methods

```kotlin
class CameraKStateHolder {
    // Attach a plugin
    fun attachPlugin(plugin: CameraKPlugin)

    // Detach a plugin
    fun detachPlugin(plugin: CameraKPlugin)

    // Wait for camera ready and get controller
    suspend fun getReadyCameraController(): CameraController?

    // Scope for plugin operations (auto-cancelled on cleanup)
    val pluginScope: CoroutineScope

    // Observe camera state
    val cameraState: StateFlow<CameraKState>

    // Observe events (QR scans, OCR results, captures, etc.)
    val events: SharedFlow<CameraKEvent>
}
```

## Performance Tips

1. **Limit plugins** — Each plugin adds processing overhead
2. **Throttle frame processing** — Don't process every frame
3. **Use background threads** — Keep processing off main thread
4. **Cleanup properly** — Release resources in `onDetach`

## Common Issues

### Plugin Not Activating

**Cause:** Plugin added after camera already ready.

**Solution:** Add plugins during `rememberCameraKState` initialization via `setupPlugins`.

### Multiple QR/OCR Results

**Cause:** Plugins scan continuously.

**Solution:** Debounce results or take first result only in your event collection logic.

### Performance Degradation

**Cause:** Too many plugins or heavy processing.

**Solution:** Reduce plugin count or optimize processing logic.

## Next Steps

- [CameraKStateHolder API](../api/state-holder.md) — State management details
- [Camera Capture](camera-capture.md) — Capture photos with plugins
- [Configuration](../getting-started/configuration.md) — Configure plugin behavior
