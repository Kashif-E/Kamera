# CameraK Documentation

**Modern camera SDK for Kotlin Multiplatform** -- Android, iOS, and Desktop with a unified API.

## Get Started in 60 Seconds

```kotlin
dependencies {
    implementation("io.github.kashif-mehmood-km:camerak:0.3")
}
```

```kotlin
@Composable
fun CameraScreen() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState()

    CameraKScreen(
        cameraState = cameraState,
        showPreview = true
    ) { readyState ->
        // Camera preview shown automatically
        FloatingActionButton(
            onClick = {
                scope.launch {
                    readyState.controller.takePictureToFile()
                }
            }
        ) {
            Icon(Icons.Default.CameraAlt, "Capture")
        }
    }
}
```

**That's it!** `CameraKScreen` handles all state management automatically.

## Features

- **Cross-Platform**: Single API works on Android, iOS, and Desktop
- **Compose-First**: Built for Jetpack Compose with reactive StateFlow
- **Plugin System**: Add QR scanning, OCR, and custom processing
- **Performance**: Direct file capture avoids memory overhead
- **Type-Safe**: Sealed classes for errors, no runtime surprises

## Installation

Start with installation and configuration:

- [Installation](getting-started/installation.md) -- Add CameraK to your project
- [Quick Start](getting-started/quick-start.md) -- Build your first camera app in 5 minutes
- [Configuration](getting-started/configuration.md) -- Customize camera behavior
- [Android Example](examples/android.md) -- Android-specific setup

## Core Concepts

### State Management

CameraK uses reactive state management via `CameraKStateHolder`:

```kotlin
sealed class CameraKState {
    object Initializing : CameraKState()
    data class Ready(val controller: CameraController, val uiState: CameraUIState) : CameraKState()
    data class Error(val exception: Exception, val message: String, val isRetryable: Boolean) : CameraKState()
}
```

State flows automatically: `Initializing` -> `Ready` -> capture photos.

### Camera Controller

Low-level camera operations exposed when state is `Ready`:

```kotlin
expect class CameraController {
    suspend fun takePictureToFile(): ImageCaptureResult
    fun setZoom(zoom: Float)
    fun setFlashMode(mode: FlashMode)
    fun toggleCameraLens()
}
```

### Plugins

Extend camera functionality modularly:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        cameraLens = CameraLens.BACK,
    ),
    setupPlugins = { stateHolder ->
        stateHolder.attachPlugin(rememberQRScannerPlugin())
        stateHolder.attachPlugin(rememberOcrPlugin())
    },
)
```

## Quick Links

**Getting Started**
- [Installation](getting-started/installation.md)
- [Quick Start](getting-started/quick-start.md)
- [Configuration](getting-started/configuration.md)

**Guides**
- [Camera Capture](guides/camera-capture.md)
- [Flash and Torch](guides/flash-and-torch.md)
- [Zoom Control](guides/zoom-control.md)
- [Camera Switching](guides/camera-switching.md)
- [Plugins](guides/plugins.md)

**API Reference**
- [CameraKStateHolder](api/state-holder.md)
- [CameraController](api/controller.md)

**Plugins**
- [Plugin System](guides/plugins.md) -- Using and creating plugins

## Platform Requirements

| Platform | Minimum Version | Backend |
|----------|-----------------|---------|
| Android  | API 21 (5.0)    | CameraX |
| iOS      | 13.0            | AVFoundation |
| Desktop  | JDK 11+         | JavaCV |

## Support

- **Issues**: [GitHub Issues](https://github.com/Kashif-E/CameraK/issues)
- **Discussions**: [GitHub Discussions](https://github.com/Kashif-E/CameraK/discussions)
- **Examples**: [Sample Projects](https://github.com/Kashif-E/CameraK/tree/main/Sample)

## License

Apache 2.0 -- [View License](license.md)
