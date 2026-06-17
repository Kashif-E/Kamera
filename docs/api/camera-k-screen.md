# CameraKScreen

Convenience wrapper composable that simplifies camera state handling with automatic preview management.

## Overview

`CameraKScreen` is a slot-based composable that handles camera state (Initializing/Ready/Error) automatically, showing appropriate UI for each state with sensible defaults.

**Benefits:**
- Automatic state handling (no when expression needed)
- Built-in loading and error screens
- Camera preview shown automatically (optional)
- Slot-based API for custom content
- Less boilerplate than manual state handling

## Function Signature

```kotlin
@Composable
fun CameraKScreen(
    modifier: Modifier = Modifier,
    cameraState: CameraKState,
    deviceOrientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    loadingContent: @Composable () -> Unit = { DefaultLoadingScreen() },
    errorContent: @Composable (CameraKState.Error) -> Unit = { DefaultErrorScreen(it) },
    showPreview: Boolean = true,
    overlay: @Composable (CameraPreviewScope.() -> Unit)? = null,
    content: @Composable (CameraKState.Ready) -> Unit
)
```

## Parameters

### cameraState

```kotlin
cameraState: CameraKState
```

Camera state from `rememberCameraKState()`. Can be one of:
- `CameraKState.Initializing` -- Camera starting
- `CameraKState.Ready` -- Camera operational
- `CameraKState.Error` -- Initialization failed

### showPreview

```kotlin
showPreview: Boolean = true
```

Whether to automatically show camera preview when ready.

- `true` (default) -- Preview shown automatically in background
- `false` -- No preview, useful for custom preview implementations

### deviceOrientation

```kotlin
deviceOrientation: DeviceOrientation = DeviceOrientation.PORTRAIT
```

Orientation passed to the preview so frames and captures are rotated correctly. One of `PORTRAIT`, `LANDSCAPE_LEFT`, `PORTRAIT_UPSIDE_DOWN`, `LANDSCAPE_RIGHT`.

### overlay

```kotlin
overlay: @Composable (CameraPreviewScope.() -> Unit)? = null
```

Optional content drawn on top of the preview (e.g. focus reticles, grids). Receives a `CameraPreviewScope`. Defaults to `null` (no overlay).

### loadingContent

```kotlin
loadingContent: @Composable () -> Unit = { DefaultLoadingScreen() }
```

Content shown during camera initialization. Default shows loading spinner with "Initializing Camera..." text.

**Custom example:**

```kotlin
loadingContent = {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Starting camera...")
    }
}
```

### errorContent

```kotlin
errorContent: @Composable (CameraKState.Error) -> Unit = { DefaultErrorScreen(it) }
```

Content shown on camera error. Receives `CameraKState.Error` with exception, message, and isRetryable details.

**Custom example:**

```kotlin
errorContent = { error ->
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Error, "Error", tint = Color.Red)
        Text("Camera Error: ${error.message}")
        if (error.isRetryable) {
            Button(onClick = { /* retry logic */ }) {
                Text("Retry")
            }
        }
    }
}
```

### content

```kotlin
content: @Composable (CameraKState.Ready) -> Unit
```

Main content shown when camera is ready. Receives `CameraKState.Ready` with access to:
- `readyState.controller` -- Camera controller for operations
- `readyState.uiState` -- UI state with zoom, flash, torch, lens, etc.

**This is where you add your camera controls.**

## Basic Example

```kotlin
@Composable
fun SimpleCameraApp() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    CameraKScreen(
        cameraState = cameraState
    ) { readyState ->
        // Camera preview shown automatically
        // Add controls overlay
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
            Icon(Icons.Default.CameraAlt, "Capture")
        }
    }
}
```

## Custom Loading/Error Screens

```kotlin
@Composable
fun CameraWithCustomScreens() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    CameraKScreen(
        cameraState = cameraState,
        loadingContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Preparing camera...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        },
        errorContent = { error ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "Unable to start camera",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    if (error.isRetryable) {
                        Button(onClick = { /* retry */ }) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }
    ) { readyState ->
        // Camera controls
        CameraControls(controller = readyState.controller)
    }
}
```

## No Auto-Preview Mode

Disable automatic preview for custom implementations:

```kotlin
@Composable
fun CustomPreviewCamera() {
    val cameraState by rememberCameraKState()

    CameraKScreen(
        cameraState = cameraState,
        showPreview = false  // Disable automatic preview
    ) { readyState ->
        // Implement custom preview
        Box(modifier = Modifier.fillMaxSize()) {
            // Custom camera preview with filters/effects
            CustomCameraPreviewWithFilters(controller = readyState.controller)

            // Controls overlay
            CameraControls(
                controller = readyState.controller,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
```

## With Plugins

```kotlin
@Composable
fun CameraWithPlugins() {
    // Create plugins in composable scope, then attach them inside setupPlugins.
    val qrScannerPlugin = rememberQRScannerPlugin()
    val ocrPlugin = rememberOcrPlugin()
    val cameraState by rememberCameraKState(
        setupPlugins = { stateHolder ->
            stateHolder.attachPlugin(qrScannerPlugin)
            stateHolder.attachPlugin(ocrPlugin)
        }
    )

    // Listen for plugin events
    LaunchedEffect(cameraState) {
        val ready = cameraState as? CameraKState.Ready ?: return@LaunchedEffect
        // Plugin results arrive via CameraKEvent through the events SharedFlow
    }

    CameraKScreen(cameraState = cameraState) { readyState ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Capture button
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        readyState.controller.takePictureToFile()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
            ) {
                Icon(Icons.Default.CameraAlt, "Capture")
            }
        }
    }
}
```

## Complete Camera App

Full-featured camera with all controls:

```kotlin
@Composable
fun FullFeaturedCamera() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState(
        config = CameraConfiguration(
            cameraLens = CameraLens.BACK,
            flashMode = FlashMode.AUTO,
            aspectRatio = AspectRatio.RATIO_16_9
        )
    )

    CameraKScreen(cameraState = cameraState) { readyState ->
        val controller = readyState.controller
        val uiState = readyState.uiState

        Box(modifier = Modifier.fillMaxSize()) {
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
                        when (uiState.flashMode) {
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
                    Icon(Icons.Default.Cameraswitch, "Switch Camera")
                }
            }

            // Zoom indicator
            if (uiState.zoomLevel > 1.0f) {
                Text(
                    text = "${String.format("%.1f", uiState.zoomLevel)}x",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White
                )
            }

            // Pinch to zoom
            val maxZoom = uiState.maxZoom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val newZoom = (uiState.zoomLevel * zoom).coerceIn(1f, maxZoom)
                            controller.setZoom(newZoom)
                        }
                    }
            )

            // Capture button
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        when (val result = controller.takePictureToFile()) {
                            is ImageCaptureResult.SuccessWithFile -> {
                                // Show success toast
                            }
                            is ImageCaptureResult.Error -> {
                                // Show error
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
            ) {
                if (uiState.isCapturing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(Icons.Default.CameraAlt, "Capture")
                }
            }
        }
    }
}
```

## Comparison: Manual vs CameraKScreen

### Manual State Handling

```kotlin
@Composable
fun ManualApproach() {
    val cameraState by rememberCameraKState()

    when (cameraState) {
        CameraKState.Initializing -> {
            CircularProgressIndicator()
        }
        is CameraKState.Ready -> {
            val ready = cameraState as CameraKState.Ready
            CameraPreviewView(controller = ready.controller)
            // Your UI here
        }
        is CameraKState.Error -> {
            Text("Error: ${(cameraState as CameraKState.Error).message}")
        }
    }
}
```

### Using CameraKScreen

```kotlin
@Composable
fun WithCameraKScreen() {
    val cameraState by rememberCameraKState()

    CameraKScreen(cameraState = cameraState) { readyState ->
        // Your UI here - loading/error/preview handled automatically
    }
}
```

**Result:** 60% less boilerplate, same functionality.

## Default Screens

### DefaultLoadingScreen

Shows:
- Black background
- White circular progress indicator
- "Initializing Camera..." text

### DefaultErrorScreen

Shows:
- Black background
- "Camera Error" title in red
- Error message in white
- "Please try again" hint (if retryable)

**Override these by passing custom `loadingContent` and `errorContent`.**

## See Also

- [CameraKStateHolder](state-holder.md) -- State management
- [CameraController](controller.md) -- Camera operations
- [Quick Start](../getting-started/quick-start.md) -- Usage examples
