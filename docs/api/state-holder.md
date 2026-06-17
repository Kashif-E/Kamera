# CameraKStateHolder API

Core state management for Kamera. Manages camera lifecycle, exposes reactive state, and handles plugin coordination.

## Overview

`CameraKStateHolder` is the primary class for camera operations. It manages:
- Camera lifecycle (initialization -> ready -> cleanup)
- Reactive state via `StateFlow`
- Plugin lifecycle and coordination
- One-shot events via `SharedFlow`

## Creation

```kotlin
@Composable
expect fun rememberCameraKState(
    config: CameraConfiguration = CameraConfiguration(),
    setupPlugins: suspend (CameraKStateHolder) -> Unit = {},
): State<CameraKState>
```

**Parameters:**
- `config` -- Camera configuration data class with defaults
- `setupPlugins` -- Suspend function to attach plugins to the state holder

**Example:**

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        cameraLens = CameraLens.BACK,
        flashMode = FlashMode.AUTO,
        aspectRatio = AspectRatio.RATIO_16_9
    ),
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(qrScannerPlugin)
        stateHolder.attachPlugin(ocrPlugin)
    }
)
```

> Create plugins with their `remember…Plugin(...)` factories in composable scope first (e.g. `val qrScannerPlugin = rememberQRScannerPlugin()`), then attach those instances inside `setupPlugins`. The factories are `@Composable` and cannot be called inside the `setupPlugins` lambda.

## Properties

### cameraState

```kotlin
val cameraState: StateFlow<CameraKState>
```

Observable camera lifecycle state. Emits:
- `CameraKState.Initializing` -- Camera starting
- `CameraKState.Ready(controller, uiState)` -- Camera operational
- `CameraKState.Error(exception, message, isRetryable)` -- Initialization failed

**Usage:**

```kotlin
val cameraState by rememberCameraKState()

when (cameraState) {
    is CameraKState.Initializing -> CircularProgressIndicator()
    is CameraKState.Ready -> {
        val ready = cameraState as CameraKState.Ready
        CameraPreviewView(controller = ready.controller)
    }
    is CameraKState.Error -> {
        val error = cameraState as CameraKState.Error
        Text("Error: ${error.message}")
    }
}
```

### uiState

```kotlin
val uiState: StateFlow<CameraUIState>
```

Observable UI properties (zoom level, flash mode, capturing state, etc.)

**CameraUIState structure:**

```kotlin
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
```

**Usage:**

```kotlin
val uiState by stateHolder.uiState.collectAsStateWithLifecycle()

Text("Zoom: ${uiState.zoomLevel}x")
Text("Max: ${uiState.maxZoom}x")
```

### events

```kotlin
val events: SharedFlow<CameraKEvent>
```

One-shot events (not persisted). Collect to handle events like capture success/failure.

**CameraKEvent types:**

```kotlin
sealed class CameraKEvent {
    data object None : CameraKEvent()
    data class ImageCaptured(val result: ImageCaptureResult) : CameraKEvent()
    data class CaptureFailed(val exception: Exception) : CameraKEvent()
    data class QRCodeScanned(val qrCode: String) : CameraKEvent()
    data class TextRecognized(val text: String) : CameraKEvent()
    data class PermissionDenied(val permission: String) : CameraKEvent()
    data class RecordingStarted(val filePath: String) : CameraKEvent()
    data class RecordingStopped(val result: VideoCaptureResult) : CameraKEvent()
    data class RecordingFailed(val exception: Exception) : CameraKEvent()
    data class RecordingMaxDurationReached(val filePath: String, val durationMs: Long) : CameraKEvent()
}
```

**Usage:**

```kotlin
LaunchedEffect(Unit) {
    stateHolder.events.collect { event ->
        when (event) {
            is CameraKEvent.ImageCaptured -> {
                showToast("Photo captured!")
            }
            is CameraKEvent.CaptureFailed -> {
                showToast("Capture failed: ${event.exception.message}")
            }
            is CameraKEvent.QRCodeScanned -> {
                showToast("QR: ${event.qrCode}")
            }
            is CameraKEvent.TextRecognized -> {
                showToast("OCR: ${event.text}")
            }
            else -> {}
        }
    }
}
```

### pluginScope

```kotlin
val pluginScope: CoroutineScope
```

CoroutineScope for plugin operations. Automatically cancelled when state holder is disposed.

## Methods

### getController()

```kotlin
fun getController(): CameraController?
```

Get camera controller if available (state is `Ready`).

**Returns:** `CameraController` or `null` if camera not ready.

**Example:**

```kotlin
val controller = stateHolder.getController()
if (controller != null) {
    controller.setZoom(2.0f)
} else {
    println("Camera not ready")
}
```

### getReadyCameraController()

```kotlin
suspend fun getReadyCameraController(): CameraController?
```

Suspends until camera is ready, then returns controller. Use in plugins.

**Returns:** `CameraController` or `null` if error occurs.

**Example:**

```kotlin
scope.launch {
    val controller = stateHolder.getReadyCameraController()
    controller?.setFlashMode(FlashMode.ON)
}
```

### captureImage()

```kotlin
fun captureImage()
```

Captures an image and emits `CameraKEvent.ImageCaptured` or `CameraKEvent.CaptureFailed`.

**Example:**

```kotlin
Button(onClick = { stateHolder.captureImage() }) {
    Text("Capture")
}

// Listen for result
LaunchedEffect(Unit) {
    stateHolder.events.collect { event ->
        when (event) {
            is CameraKEvent.ImageCaptured -> {
                when (val result = event.result) {
                    is ImageCaptureResult.SuccessWithFile -> {
                        println("Saved: ${result.filePath}")
                    }
                    is ImageCaptureResult.Error -> {
                        println("Error: ${result.exception.message}")
                    }
                }
            }
        }
    }
}
```

### setZoom()

```kotlin
fun setZoom(zoom: Float)
```

Sets zoom level. Updates `uiState.zoomLevel`.

**Parameters:**
- `zoom` -- Zoom level (1.0 to maxZoom)

**Example:**

```kotlin
stateHolder.setZoom(2.5f)
```

### toggleFlashMode()

```kotlin
fun toggleFlashMode()
```

Cycles through flash modes: OFF -> ON -> AUTO -> OFF

**Example:**

```kotlin
IconButton(onClick = { stateHolder.toggleFlashMode() }) {
    Icon(Icons.Default.FlashOn, "Toggle Flash")
}
```

### setFlashMode()

```kotlin
fun setFlashMode(mode: FlashMode)
```

Sets flash mode directly.

**Example:**

```kotlin
stateHolder.setFlashMode(FlashMode.AUTO)
```

### toggleTorchMode()

```kotlin
fun toggleTorchMode()
```

Toggles torch ON / OFF.

**Example:**

```kotlin
IconButton(onClick = { stateHolder.toggleTorchMode() }) {
    Icon(Icons.Default.FlashlightOn, "Toggle Torch")
}
```

### setTorchMode()

```kotlin
fun setTorchMode(mode: TorchMode)
```

Sets torch mode directly.

**Example:**

```kotlin
stateHolder.setTorchMode(TorchMode.ON)
```

### toggleCameraLens()

```kotlin
fun toggleCameraLens()
```

Switches between front and back cameras.

**Example:**

```kotlin
IconButton(onClick = { stateHolder.toggleCameraLens() }) {
    Icon(Icons.Default.Cameraswitch, "Switch Camera")
}
```

### attachPlugin()

```kotlin
fun attachPlugin(plugin: CameraKPlugin)
```

Attaches a plugin to the state holder. The plugin's `onAttach` method is called.

### detachPlugin()

```kotlin
fun detachPlugin(plugin: CameraKPlugin)
```

Detaches a plugin from the state holder. The plugin's `onDetach` method is called.

### updateUIState()

```kotlin
fun updateUIState(update: (CameraUIState) -> CameraUIState)
```

Updates the UI state using a transformation function.

### emitEvent()

```kotlin
suspend fun emitEvent(event: CameraKEvent)
```

Emits an event to the events SharedFlow.

### initialize()

```kotlin
suspend fun initialize()
```

Initializes camera. Called automatically by `rememberCameraKState`. Don't call manually.

### shutdown()

```kotlin
fun shutdown()
```

Cleans up resources. Called automatically when composable leaves composition.

## Complete Example

```kotlin
@Composable
fun CompleteStateHolderExample() {
    val scope = rememberCoroutineScope()
    val qrScannerPlugin = rememberQRScannerPlugin()

    val cameraState by rememberCameraKState(
        config = CameraConfiguration(
            cameraLens = CameraLens.BACK,
            flashMode = FlashMode.AUTO,
            aspectRatio = AspectRatio.RATIO_16_9
        ),
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(qrScannerPlugin)
        }
    )

    // Listen for events
    val readyState = cameraState as? CameraKState.Ready

    LaunchedEffect(readyState) {
        readyState ?: return@LaunchedEffect
        // Events can be collected from the controller's parent state holder
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller
                val uiState = state.uiState

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // UI State display
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                ) {
                    Text("Zoom: ${uiState.zoomLevel}x / ${uiState.maxZoom}x")
                    Text("Flash: ${uiState.flashMode}")
                    Text("Lens: ${uiState.cameraLens}")
                }

                // Capture button
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
                    if (uiState.isCapturing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.CameraAlt, "Capture")
                    }
                }

                // Zoom slider
                Slider(
                    value = uiState.zoomLevel,
                    onValueChange = { controller.setZoom(it) },
                    valueRange = 1f..uiState.maxZoom,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(200.dp)
                        .graphicsLayer { rotationZ = 270f }
                )
            }

            is CameraKState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera error: ${state.message}")
                    if (state.isRetryable) {
                        Button(onClick = { /* retry */ }) {
                            Text("Retry")
                        }
                    }
                }
            }

            CameraKState.Initializing -> {
                CircularProgressIndicator()
            }
        }
    }
}
```

## Thread Safety

All public methods are thread-safe. State updates are synchronized internally.

## Lifecycle

1. **Creation** -- `rememberCameraKState()` creates state holder
2. **Initialization** -- Camera hardware initialized
3. **Ready** -- Camera operational, plugins activated
4. **Cleanup** -- Resources released when composable leaves composition

## See Also

- [CameraController API](controller.md) -- Low-level camera operations
- [Configuration](../getting-started/configuration.md) -- Configuration options
- [Camera Capture Guide](../guides/camera-capture.md) -- Capture examples
