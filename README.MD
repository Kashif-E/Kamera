# Kamera

<p align="center">
  <img src="docs/assets/kamera.jpg" alt="Kamera Poster" width="800" style="border-radius: 50%;"/>
</p>

<p align="center">
A camera library for Kotlin Multiplatform and Compose Multiplatform: Android (CameraX), iOS (AVFoundation) and desktop JVM (JavaCV) behind one API.
</p>

<p align="center">

[![Maven Central](https://img.shields.io/maven-central/v/dev.kashifmehmood/camerak?label=Maven%20Central&color=blue)](https://search.maven.org/search?q=g:dev.kashifmehmood)
[![GitHub Release](https://img.shields.io/github/v/release/kashif-e/Kamera)](https://github.com/Kashif-E/Kamera/releases)
[![Kotlin Weekly](https://img.shields.io/badge/Kotlin_Weekly-425-7F52FF)](https://mailchi.mp/kotlinweekly/kotlin-weekly-425)
[![Kotlin Weekly](https://img.shields.io/badge/Kotlin_Weekly-517-7F52FF)](https://mailchi.mp/kotlinweekly/kotlin-weekly-517)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
</p>

Upgrading from 1.x? Read the [2.0 migration guide](docs/migration/2.0.md).

## Features

- **One state holder**: `CameraKStateHolder` owns the camera. Its state is `StateFlow`s and its commands are suspend functions that return a `CameraKResult`, so every failure is a typed `CameraKError`.
- **Compose, or not**: `camerak` adds `rememberCameraKStateHolder`, `CameraKScreen` and `CameraPreview`; `camerak-core` is the same camera with no Compose.
- **Survives rotation**: the Compose holder lives in a ViewModel, so a rotation keeps the camera and any recording.
- **Capture and recording**: photos to app-owned files with `capture()`, video with pause and resume, limits and an elapsed-time state.
- **Lens control**: zoom, flash, torch, front and back, ultra-wide and telephoto, and per-camera capability queries.
- **Focus and exposure**: tap to focus on the preview, a focus point and exposure compensation on the holder. Android and iOS support both; desktop cameras do not support it.
- **Plugins**: QR and barcode scanning, OCR, an analyzer that hands you encoded frames, and a media saver that publishes to the gallery. Your own analysis uses the same frame source.
- **Testable**: `camerak-testing` gives your tests a scripted fake camera.

## Installation

Add the dependencies to your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // The camera with its Compose UI; camerak-core alone for an app without Compose
            implementation("dev.kashifmehmood:camerak:2.0")

            // Optional plugins
            implementation("dev.kashifmehmood:camerak-media-saver:2.0")
            implementation("dev.kashifmehmood:camerak-qr-scanner:2.0")
            implementation("dev.kashifmehmood:camerak-ocr:2.0")
            implementation("dev.kashifmehmood:camerak-analyzer:2.0")
        }
        commonTest.dependencies {
            // The fake camera, for tests only
            implementation("dev.kashifmehmood:camerak-testing:2.0")
        }
    }
}
```

`camerak-bom` pins every CameraK artifact to one version: add `implementation(project.dependencies.platform("dev.kashifmehmood:camerak-bom:2.0"))` and leave the versions off the others. The [installation guide](docs/getting-started/installation.md) also covers version catalogs.

### Platform Setup

**Android** - CameraK's own manifest already merges `CAMERA`. Add what your features need to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<!-- Recording with audio only -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- MediaSaverPlugin below API 29 only; the saver's manifest already declares it with this cap -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

`RECORD_AUDIO` is needed only for a recording with `enableAudio = true` in its `VideoConfiguration`. `WRITE_EXTERNAL_STORAGE` grants nothing from API 29, where MediaStore needs no permission for the app's own media, so keep it capped at 28.

**iOS** - Add the usage descriptions to `Info.plist`. iOS terminates an app that asks for a permission whose usage description is missing, so declare every key for the features you use:

```xml
<!-- Every camera session -->
<key>NSCameraUsageDescription</key>
<string>Camera access is needed to take photos and videos</string>
<!-- Recording with audio (enableAudio = true) -->
<key>NSMicrophoneUsageDescription</key>
<string>Microphone access is needed to record sound with videos</string>
<!-- Saving to Photos with MediaSaverPlugin -->
<key>NSPhotoLibraryAddUsageDescription</key>
<string>Photo library access is needed to save your photos and videos</string>
<!-- Reading a saved photo back with MediaSaverPlugin.readBytes -->
<key>NSPhotoLibraryUsageDescription</key>
<string>Photo library access is needed to show the photos you saved</string>
```

| Key | Needed for |
|-----|-----------|
| `NSCameraUsageDescription` | Every camera session |
| `NSMicrophoneUsageDescription` | A recording with `enableAudio = true` |
| `NSPhotoLibraryAddUsageDescription` | Saving captures or recordings to Photos with `MediaSaverPlugin` |
| `NSPhotoLibraryUsageDescription` | Reading a saved photo back with `MediaSaverPlugin.readBytes` |

`MediaSaverPlugin.readBytes` fetches the saved asset from Photos by its identifier, which the add-only access that CameraK requests cannot do, so an app that calls it asks for read access itself. Set the app's deployment target to iOS 16.0 or later.

**Desktop** - CameraK depends only on bytedeco's Java bindings (`javacv`, `javacpp`, `ffmpeg`, `videoinput`; the OCR plugin adds `tesseract` and `leptonica`), so your app chooses which operating systems' natives it ships. Windows captures through FFmpeg's dshow input, so it needs the `javacpp` and `ffmpeg` natives like the other platforms and no `videoinput` natives: CameraK does not load them. Either declare the classifier jars for each platform you ship:

```kotlin
// desktopMain, one line per library and per platform (macosx-arm64, macosx-x86_64, linux-x86_64,
// linux-arm64, windows-x86_64)
runtimeOnly("org.bytedeco:javacpp:1.5.12:macosx-arm64")
runtimeOnly("org.bytedeco:ffmpeg:7.1.1-1.5.12:macosx-arm64")
runtimeOnly("org.bytedeco:tesseract:5.5.1-1.5.12:macosx-arm64")     // camerak-ocr only
runtimeOnly("org.bytedeco:leptonica:1.85.0-1.5.12:macosx-arm64")    // camerak-ocr only
```

or depend on the `-platform` artifacts (`ffmpeg-platform`, `javacpp-platform`, ...) and narrow them with the [`org.bytedeco.gradle-javacpp-platform`](https://github.com/bytedeco/gradle-javacpp) plugin. Without natives the camera does not open: the holder's state becomes `CameraKState.Error` with `CameraKError.NativeLibraryMissing`, which names the bytedeco platform and keeps JavaCV's failure as its cause. A packaged macOS app also needs `NSCameraUsageDescription` in its bundle; the [installation guide](docs/getting-started/installation.md#desktop-jvm) shows how.

## Quick Start

```kotlin
@Composable
fun CameraScreen() {
    val holder = rememberCameraKStateHolder(
        config = CameraConfiguration {
            cameraLens = CameraLens.BACK
            aspectRatio = AspectRatio.RATIO_16_9
        },
    )
    val scope = rememberCoroutineScope()

    CameraKScreen(
        holder = holder,
        loading = { CircularProgressIndicator() },
        error = { error, retry ->
            Column {
                Text("Camera unavailable: ${error::class.simpleName}")
                if (error.isRetryable) Button(onClick = retry) { Text("Try again") }
            }
        },
    ) {
        Button(
            onClick = {
                scope.launch {
                    holder.capture()
                        .onSuccess { image -> println("Saved ${image.width}x${image.height} to ${image.path}") }
                        .onFailure { error -> println("Capture failed: $error") }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).keepUpright(),
        ) { Text("Capture") }
    }
}
```

`rememberCameraKStateHolder` starts the camera when the screen reaches STARTED, stops it below, keeps it across a rotation and closes it when the screen goes away. Before it opens the camera it checks the permission; ask for it with `rememberCameraKPermissionState`:

```kotlin
val permissions = rememberCameraKPermissionState(CameraKPermission.CAMERA)
if (permissions.allGranted) {
    CameraScreen()
} else {
    Button(onClick = permissions::launchRequest) { Text("Allow camera") }
}
```

### Camera state

`holder.cameraState` is a closed hierarchy, so a `when` over it needs no `else`:

| State | Meaning |
|-------|---------|
| `Idle` | Not started, or stopped because the screen left STARTED |
| `Starting` | Opening the camera |
| `Ready` | The camera is open; commands run |
| `Interrupted(reason)` | The system holds the camera (a call, multitasking); keep the controls reachable |
| `Error(error)` | The camera failed; `error` is a `CameraKError`, and `retry()` starts again |
| `Closed` | `close()` released the camera for good |

### Commands

Every command is a suspend function that returns `CameraKResult`, with `onSuccess`, `onFailure`, `fold`, `getOrNull` and `errorOrNull`:

```kotlin
scope.launch {
    holder.capture()                                // CameraKResult<CapturedImage>
    holder.setZoom(2f)                              // clamped to the camera's range
    holder.cycleFlashMode()                         // OFF, AUTO, ON as the camera offers them
    holder.toggleTorch()
    holder.toggleLens()                             // front and back
    holder.selectCamera(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE)
    holder.startRecording(VideoConfiguration { maxDuration = 30.seconds })
    holder.stopRecording()                          // CameraKResult<RecordedVideo>
}
```

Read the camera's live properties from `holder.uiState` (flash, torch, lens, device type, captures in flight), `holder.zoom`, `holder.capabilities` and `holder.recordingState`.

## Platform Support

| Platform | Min Version | Backend |
|----------|-------------|---------|
| Android | API 23+ | CameraX |
| iOS | iOS 16.0+ (arm64 devices and Apple-silicon simulators) | AVFoundation |
| Desktop | JDK 17+ | JavaCV |

## Plugins

Pass plugins when you create the holder, so a plugin that reads frames gets them from the first bind:

```kotlin
val qrScanner = rememberQRScannerPlugin()
val saver = rememberMediaSaverPlugin(MediaSaverConfig { autoSave = true })
val holder = rememberCameraKStateHolder(plugins = listOf(qrScanner, saver))

LaunchedEffect(qrScanner) {
    qrScanner.results.collect { barcode -> println(barcode.rawValue) }
}
```

| Artifact | Plugin | What it does |
|----------|--------|--------------|
| `camerak-qr-scanner` | `QRScannerPlugin` | QR codes and barcodes from the analysis frames |
| `camerak-ocr` | `OcrPlugin` | Live text, and `recognize(image)` for a capture |
| `camerak-analyzer` | `AnalyzerPlugin` | JPEG-encoded frames for your own analysis |
| `camerak-media-saver` | `MediaSaverPlugin` | Publishes captures and recordings to the gallery |

Each plugin has `results`, a `status` and, for the frame readers, `pause()` and `resume()`. For analysis of your own, register on `holder.frames`; to write a plugin, implement `CameraKPlugin`. See the [plugins guide](docs/guides/plugins.md).

## Documentation

- [Installation](docs/getting-started/installation.md) and [Quick Start](docs/getting-started/quick-start.md)
- [Configuration](docs/getting-started/configuration.md)
- Guides: [capture](docs/guides/camera-capture.md), [flash and torch](docs/guides/flash-and-torch.md), [zoom](docs/guides/zoom-control.md), [focus and exposure](docs/guides/focus-and-exposure.md), [switching cameras](docs/guides/camera-switching.md), [video recording](docs/guides/video-recording.md), [plugins](docs/guides/plugins.md), [testing](docs/guides/testing.md)
- [Migrating from 1.x](docs/migration/2.0.md)
- [Troubleshooting](docs/troubleshooting.md)

### Debug Logging

CameraK logs nothing unless you turn it on:

```kotlin
CameraKLogger.enabled = true
```

Log messages name the operation and its outcome, never a path, a recognized text or a barcode value.

## Contributing

Contributions welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md), open an issue to discuss the change, then send a pull request from a feature branch.

## Support

If you find this library useful:

<a href="https://www.buymeacoffee.com/kashifmehmood"><img src="https://img.buymeacoffee.com/button-api/?text=Buy me a coffee&emoji=&slug=kashifmehmood&button_colour=FFDD00&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=ffffff" /></a>

## License

Every CameraK release is licensed under Apache 2.0, including 1.x releases whose POMs said MIT by mistake.

```
Apache License 2.0

Copyright 2025 Kashif Mehmood

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
