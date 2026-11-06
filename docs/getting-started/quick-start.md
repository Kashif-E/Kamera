# Quick Start

Build your first camera app in 5 minutes.

## Basic Camera Screen

```kotlin
@Composable
fun CameraScreen() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                // Camera preview
                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // Capture button
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            when (val result = controller.takePictureToFile()) {
                                is ImageCaptureResult.SuccessWithFile -> {
                                    println("Photo saved to: ${result.filePath}")
                                }
                                is ImageCaptureResult.Error -> {
                                    println("Capture failed: ${result.exception.message}")
                                }
                                is ImageCaptureResult.Success -> Unit // not returned by takePictureToFile()
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                }
            }

            is CameraKState.Error -> {
                Text(
                    text = "Camera Error: ${state.message}",
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            CameraKState.Initializing -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
```

**That's it!** You now have a working camera app.

## Alternative: Using `CameraKScreen`

For even less boilerplate, use the `CameraKScreen` helper that handles state automatically:

```kotlin
@Composable
fun SimpleCameraScreen() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    CameraKScreen(
        cameraState = cameraState,
        showPreview = true,
        loadingContent = {
            // Optional: Custom loading UI
            CircularProgressIndicator()
        },
        errorContent = { error ->
            // Optional: Custom error UI
            Text("Camera Error: ${error.message}")
        }
    ) { readyState ->
        // Camera preview is shown automatically
        // Add your UI overlay here
        FloatingActionButton(
            onClick = {
                scope.launch {
                    readyState.controller.takePictureToFile()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
        }
    }
}
```

**Benefits:**
- Automatic state handling (no when expression needed)
- Built-in loading and error screens
- Camera preview shown automatically
- Less boilerplate code

## What's Happening?

### 1. Camera State Management

```kotlin
val cameraState by rememberCameraKState()
```

Creates and remembers camera state that manages the camera lifecycle. Returns a `State<CameraKState>` that flows through:
- `Initializing` -- Camera starting
- `Ready` -- Camera operational (provides `controller` and `uiState`)
- `Error` -- Something went wrong (provides `exception`, `message`, and `isRetryable`)

### 2. Camera Preview

```kotlin
CameraPreviewView(
    controller = controller,
    modifier = Modifier.fillMaxSize()
)
```

Displays live camera feed. Only available when state is `Ready`.

### 3. Capture Photos

```kotlin
when (val result = controller.takePictureToFile()) {
    is ImageCaptureResult.SuccessWithFile -> {
        // result.filePath contains the saved image path
    }
    is ImageCaptureResult.Error -> {
        // result.exception contains the error
    }
    is ImageCaptureResult.Success -> Unit // not returned by takePictureToFile()
}
```

Captures and saves photo directly to file. Returns sealed class with type-safe results.

## Add Flash Control

```kotlin
@Composable
fun CameraScreenWithFlash() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // Flash toggle button
                IconButton(
                    onClick = { controller.toggleFlashMode() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = when (controller.getFlashMode()) {
                            FlashMode.ON -> Icons.Default.FlashOn
                            FlashMode.OFF -> Icons.Default.FlashOff
                            FlashMode.AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash"
                    )
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
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                }
            }

            is CameraKState.Error -> {
                Text("Error: ${state.message}")
            }

            CameraKState.Initializing -> {
                CircularProgressIndicator()
            }
        }
    }
}
```

**New**: `toggleFlashMode()` cycles through OFF -> ON -> AUTO.

## Add Camera Switching

```kotlin
@Composable
fun CameraScreenWithSwitching() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Flash toggle
                    IconButton(onClick = { controller.toggleFlashMode() }) {
                        Icon(Icons.Default.FlashOn, contentDescription = "Flash")
                    }

                    // Camera switch
                    IconButton(onClick = { controller.toggleCameraLens() }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera")
                    }
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
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                }
            }

            is CameraKState.Error -> {
                Text("Error: ${state.message}")
            }

            CameraKState.Initializing -> {
                CircularProgressIndicator()
            }
        }
    }
}
```

**New**: `toggleCameraLens()` switches between front and back cameras.

## Configuration Options

Customize camera behavior during initialization:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        cameraLens = CameraLens.FRONT,           // Start with front camera
        flashMode = FlashMode.OFF,               // Flash off by default
        aspectRatio = AspectRatio.RATIO_16_9,    // 16:9 widescreen
        imageFormat = ImageFormat.JPEG,           // JPEG compression
        directory = Directory.PICTURES,           // Save to Pictures folder
    )
)
```

See [Configuration Guide](configuration.md) for all options.

## Add Plugins

Enable QR scanning or OCR:

```kotlin
val qrScannerPlugin = rememberQRScannerPlugin()
val ocrPlugin = rememberOcrPlugin()

val cameraState by rememberCameraKState(
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(qrScannerPlugin)
        stateHolder.attachPlugin(ocrPlugin)
    }
)
```

See [Plugins Guide](../guides/plugins.md) for details.

## Next Steps

- [Configuration](configuration.md) -- Customize camera settings
- [CameraKScreen API](../api/camera-k-screen.md) -- Convenience wrapper with automatic state handling
- [CameraKStateHolder API](../api/state-holder.md) -- State management details
- [CameraController API](../api/controller.md) -- Low-level camera operations
- [Camera Capture Guide](../guides/camera-capture.md) -- Advanced capture techniques
- [Flash and Torch](../guides/flash-and-torch.md) -- Lighting control
- [Zoom Control](../guides/zoom-control.md) -- Pinch-to-zoom
- [Plugins](../guides/plugins.md) -- Extend functionality
