# Kamera Documentation

**A camera library for Kotlin Multiplatform**: Android (CameraX), iOS (AVFoundation) and desktop JVM (JavaCV) behind one API, with Compose Multiplatform UI on top.

Upgrading from 1.x? Start with the [2.0 migration guide](migration/2.0.md).

## Get Started in 60 Seconds

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kashif-mehmood-km:camerak:2.0")
        }
    }
}
```

```kotlin
@Composable
fun CameraScreen() {
    val holder = rememberCameraKStateHolder()
    val scope = rememberCoroutineScope()

    CameraKScreen(holder, loading = { CircularProgressIndicator() }) {
        FloatingActionButton(
            onClick = { scope.launch { holder.capture() } },
            modifier = Modifier.align(Alignment.BottomCenter).keepUpright(),
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
        }
    }
}
```

`rememberCameraKStateHolder` opens the camera while the screen is started, keeps it across a rotation and closes it when the screen goes away. `CameraKScreen` shows the preview with your controls over it.

## Core Concepts

### The state holder

`CameraKStateHolder` owns one camera. What it reports is `StateFlow`s:

- `cameraState`: `Idle`, `Starting`, `Ready`, `Interrupted(reason)`, `Error(error)`, `Closed`. Closed, so a `when` needs no `else`.
- `uiState`: the bound camera's flash, torch, lens and device type, and the captures in flight, as the platform reports them.
- `zoom`, `capabilities`, `deviceOrientation`, `recordingState`.

What it does is suspend commands that return `CameraKResult<T>`: `capture()`, `setZoom(ratio)`, `setFlashMode(mode)`, `setTorchMode(mode)`, `selectCamera(...)`, `startRecording(config)`, `stopRecording()` and more. A failure is a typed `CameraKError` that says whether a retry can succeed.

### Plugins and frames

Plugins add scanning, OCR, encoded frames and gallery saving. They read the same frame source an app reads through `holder.frames`, and they are passed when the holder is created:

```kotlin
val scanner = rememberQRScannerPlugin()
val holder = rememberCameraKStateHolder(plugins = listOf(scanner))
```

## Guides

**Getting Started**: [Installation](getting-started/installation.md), [Quick Start](getting-started/quick-start.md), [Configuration](getting-started/configuration.md)

**Guides**: [Camera Capture](guides/camera-capture.md), [Flash and Torch](guides/flash-and-torch.md), [Zoom Control](guides/zoom-control.md), [Camera Switching](guides/camera-switching.md), [Video Recording](guides/video-recording.md), [Plugins](guides/plugins.md), [Testing](guides/testing.md)

**API Reference**: [CameraKStateHolder](api/state-holder.md), [CameraKScreen and CameraPreview](api/camera-k-screen.md)

**Migrating**: [From 1.x to 2.0](migration/2.0.md)

## Platform Requirements

| Platform | Minimum Version | Backend |
|----------|-----------------|---------|
| Android  | API 23 (6.0)    | CameraX |
| iOS      | iOS 16.0+ (arm64 devices and Apple-silicon simulators) | AVFoundation |
| Desktop  | JDK 17+         | JavaCV |

## Support

- **Issues**: [GitHub Issues](https://github.com/Kashif-E/Kamera/issues)
- **Discussions**: [GitHub Discussions](https://github.com/Kashif-E/Kamera/discussions)
- **Examples**: [Sample app](https://github.com/Kashif-E/Kamera/tree/main/Sample)

## License

Apache License 2.0. See [License](license.md).
