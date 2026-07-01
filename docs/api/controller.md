# CameraController API

Low-level camera operations. Available when camera state is `Ready`.

## Accessing Controller

```kotlin
val cameraState by rememberCameraKState()

when (cameraState) {
    is CameraKState.Ready -> {
        val controller = (cameraState as CameraKState.Ready).controller
        // Use controller here
    }
}
```

## Image Capture

### takePictureToFile()

**Recommended** -- Captures image directly to file.

```kotlin
suspend fun takePictureToFile(): ImageCaptureResult
```

**Returns:**
- `ImageCaptureResult.SuccessWithFile(filePath: String)`
- `ImageCaptureResult.Error(exception: Exception)`

**Example:**

```kotlin
scope.launch {
    when (val result = controller.takePictureToFile()) {
        is ImageCaptureResult.SuccessWithFile -> {
            println("Saved: ${result.filePath}")
        }
        is ImageCaptureResult.Error -> {
            println("Error: ${result.exception.message}")
        }
    }
}
```

**Benefits:**
- Direct file save, no ByteArray conversion
- Lower memory usage

## Zoom Control

### setZoom()

```kotlin
fun setZoom(zoomRatio: Float)
```

Sets zoom level between 1.0 and maxZoom.

**Parameters:**
- `zoomRatio` -- Zoom level (1.0 = no zoom)

**Example:**

```kotlin
controller.setZoom(2.5f)  // 2.5x zoom
```

### getZoom()

```kotlin
fun getZoom(): Float
```

Returns current zoom level.

**Example:**

```kotlin
val currentZoom = controller.getZoom()  // e.g., 2.5
```

### getMaxZoom()

```kotlin
fun getMaxZoom(): Float
```

Returns maximum supported zoom.

**Example:**

```kotlin
val maxZoom = controller.getMaxZoom()  // e.g., 10.0
```

## Flash Control

### setFlashMode()

```kotlin
fun setFlashMode(mode: FlashMode)
```

Sets flash mode.

**Parameters:**
- `FlashMode.ON` -- Always fire flash
- `FlashMode.OFF` -- Flash disabled
- `FlashMode.AUTO` -- Flash in low light

**Example:**

```kotlin
controller.setFlashMode(FlashMode.ON)
```

### getFlashMode()

```kotlin
fun getFlashMode(): FlashMode?
```

Returns current flash mode or `null` if not available.

**Example:**

```kotlin
val flashMode = controller.getFlashMode()
when (flashMode) {
    FlashMode.ON -> println("Flash enabled")
    FlashMode.OFF -> println("Flash disabled")
    FlashMode.AUTO -> println("Flash automatic")
    null -> println("Flash not available")
}
```

### toggleFlashMode()

```kotlin
fun toggleFlashMode()
```

Cycles through: OFF -> ON -> AUTO -> OFF

**Example:**

```kotlin
IconButton(onClick = { controller.toggleFlashMode() }) {
    Icon(Icons.Default.FlashOn, "Toggle Flash")
}
```

## Torch Control

### setTorchMode()

```kotlin
fun setTorchMode(mode: TorchMode)
```

Sets torch (flashlight) mode.

**Parameters:**
- `TorchMode.ON` -- Torch enabled
- `TorchMode.OFF` -- Torch disabled

**Example:**

```kotlin
controller.setTorchMode(TorchMode.ON)
```

### getTorchMode()

```kotlin
fun getTorchMode(): TorchMode?
```

Returns current torch mode or `null` if not available.

### toggleTorchMode()

```kotlin
fun toggleTorchMode()
```

Toggles torch ON / OFF.

**Example:**

```kotlin
IconButton(onClick = { controller.toggleTorchMode() }) {
    val isOn = controller.getTorchMode() == TorchMode.ON
    Icon(
        if (isOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
        "Toggle Torch"
    )
}
```

## Camera Lens

### getCameraLens()

```kotlin
fun getCameraLens(): CameraLens?
```

Returns current camera lens.

### toggleCameraLens()

```kotlin
fun toggleCameraLens()
```

Switches between front and back.

**Example:**

```kotlin
IconButton(onClick = { controller.toggleCameraLens() }) {
    Icon(Icons.Default.Cameraswitch, "Switch Camera")
}
```

## Session Management

### startSession()

```kotlin
fun startSession()
```

Starts camera session. Called automatically.

### stopSession()

```kotlin
fun stopSession()
```

Stops camera session.

**Example:**

```kotlin
DisposableEffect(Unit) {
    onDispose {
        controller.stopSession()
    }
}
```

## Listeners

### addImageCaptureListener()

```kotlin
fun addImageCaptureListener(listener: (ByteArray) -> Unit)
```

Adds listener for captured images. Used by plugins.

**Example:**

```kotlin
controller.addImageCaptureListener { imageData ->
    processImage(imageData)
}
```

## Cleanup

### cleanup()

```kotlin
fun cleanup()
```

Releases all resources. Called automatically on dispose.

**Example:**

```kotlin
DisposableEffect(Unit) {
    onDispose {
        controller.cleanup()
    }
}
```

## Complete Example

```kotlin
@Composable
fun CompleteControllerExample() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller
                val uiState = state.uiState
                val maxZoom = remember { controller.getMaxZoom() }
                var currentZoom by remember { mutableStateOf(1.0f) }

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                currentZoom = (currentZoom * zoom).coerceIn(1f, maxZoom)
                                controller.setZoom(currentZoom)
                            }
                        }
                )

                // Top controls
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Flash toggle
                    IconButton(onClick = { controller.toggleFlashMode() }) {
                        Icon(
                            when (controller.getFlashMode()) {
                                FlashMode.ON -> Icons.Default.FlashOn
                                FlashMode.OFF -> Icons.Default.FlashOff
                                FlashMode.AUTO -> Icons.Default.FlashAuto
                                else -> Icons.Default.FlashOff
                            },
                            "Flash"
                        )
                    }

                    // Camera switch
                    IconButton(onClick = { controller.toggleCameraLens() }) {
                        Icon(Icons.Default.Cameraswitch, "Switch")
                    }

                    // Torch toggle
                    IconButton(onClick = { controller.toggleTorchMode() }) {
                        Icon(
                            if (controller.getTorchMode() == TorchMode.ON)
                                Icons.Default.FlashlightOn
                            else Icons.Default.FlashlightOff,
                            "Torch"
                        )
                    }
                }

                // Zoom indicator
                Text(
                    text = "${String.format("%.1f", currentZoom)}x",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    color = Color.White
                )

                // Capture button
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            when (val result = controller.takePictureToFile()) {
                                is ImageCaptureResult.SuccessWithFile -> {
                                    println("Saved: ${result.filePath}")
                                }
                                is ImageCaptureResult.Error -> {
                                    println("Error: ${result.exception.message}")
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, "Capture")
                }
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

            CameraKState.Initializing -> CircularProgressIndicator()
        }
    }
}
```

## Platform Availability

| Method | Android | iOS | Desktop |
|--------|---------|-----|---------|
| `takePictureToFile()` | Yes | Yes | Yes |
| `setZoom()` | Yes | Yes | Limited |
| `setFlashMode()` | Yes (rear) | Yes (rear) | No |
| `setTorchMode()` | Yes (rear) | Yes (rear) | No |
| `toggleCameraLens()` | Yes | Yes | Limited |

## See Also

- [CameraKStateHolder](state-holder.md) -- State management
- [Configuration](../getting-started/configuration.md) -- Initial configuration
- [Camera Capture Guide](../guides/camera-capture.md) -- Capture examples
