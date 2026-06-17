# Configuration

Configure camera behavior, output format, and platform-specific features.

## Configuration Data Class

Pass configuration during initialization:

```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        // Your configuration here
    )
)
```

## Camera Selection

### Set Initial Camera Lens

```kotlin
config = CameraConfiguration(
    cameraLens = CameraLens.BACK  // or CameraLens.FRONT
)
```

**Options:**
- `CameraLens.BACK` -- Rear-facing camera (default)
- `CameraLens.FRONT` -- Front-facing camera

**Switch at runtime:**

```kotlin
controller.toggleCameraLens()
```

### iOS: Advanced Camera Types

On iOS devices with multiple cameras, select specific lenses:

```kotlin
config = CameraConfiguration(
    cameraDeviceType = CameraDeviceType.ULTRA_WIDE
)
```

**Options:**
- `CameraDeviceType.DEFAULT` -- Standard wide-angle (default)
- `CameraDeviceType.ULTRA_WIDE` -- Ultra-wide lens (0.5x zoom)
- `CameraDeviceType.TELEPHOTO` -- Telephoto lens (2x-3x zoom)
- `CameraDeviceType.WIDE_ANGLE` -- Wide-angle lens
- `CameraDeviceType.MACRO` -- Macro lens

**Availability:** Depends on device hardware (iPhone 11 Pro+, iPhone 13+, etc.)

## Aspect Ratio

Set the capture aspect ratio:

```kotlin
config = CameraConfiguration(
    aspectRatio = AspectRatio.RATIO_16_9
)
```

**Options:**

| Aspect Ratio | Use Case | Resolution Example |
|--------------|----------|-------------------|
| `RATIO_4_3` | Traditional photos | 2048x1536 |
| `RATIO_16_9` | Widescreen (default) | 1920x1080 |
| `RATIO_9_16` | Vertical stories | 1080x1920 |
| `RATIO_1_1` | Square photos | 1080x1080 |

**Example:**

```kotlin
// Instagram story mode
config = CameraConfiguration(
    aspectRatio = AspectRatio.RATIO_9_16
)
```

## Resolution

Optionally set specific resolution:

```kotlin
config = CameraConfiguration(
    targetResolution = 1920 to 1080  // width x height
)
```

**Common resolutions:**
- `1920 to 1080` -- Full HD
- `1280 to 720` -- HD
- `3840 to 2160` -- 4K (if supported)

**Note:** If device doesn't support exact resolution, closest match is used.

## Flash Mode

Control flash behavior:

```kotlin
config = CameraConfiguration(
    flashMode = FlashMode.AUTO
)
```

**Options:**
- `FlashMode.OFF` -- Flash disabled
- `FlashMode.ON` -- Flash always fires
- `FlashMode.AUTO` -- Flash fires in low light (default)

**Runtime control:**

```kotlin
controller.setFlashMode(FlashMode.ON)
controller.toggleFlashMode()  // Cycles: OFF -> ON -> AUTO

val currentMode = controller.getFlashMode()  // returns FlashMode?
```

## Image Format

Set output format:

```kotlin
config = CameraConfiguration(
    imageFormat = ImageFormat.JPEG
)
```

**Options:**

| Format | Compression | File Size | Use Case |
|--------|-------------|-----------|----------|
| `JPEG` | Lossy | Smaller | Web, sharing (default) |
| `PNG` | Lossless | Larger | Transparency, archival |

**Example:**

```kotlin
// High-quality archival photos
config = CameraConfiguration(
    imageFormat = ImageFormat.PNG
)
```

## Save Directory

Choose where captured images are saved:

```kotlin
config = CameraConfiguration(
    directory = Directory.PICTURES
)
```

**Options:**

| Directory | Android Path | iOS Path |
|-----------|--------------|----------|
| `PICTURES` | `Environment.DIRECTORY_PICTURES` | Photo Library (default) |
| `DCIM` | `Environment.DIRECTORY_DCIM` | Photo Library |
| `DOCUMENTS` | `getExternalFilesDir(DOCUMENTS)` | Documents folder |

**Example:**

```kotlin
// Save to DCIM folder
config = CameraConfiguration(
    directory = Directory.DCIM
)
```

## Quality Prioritization

Balance between capture speed and image quality:

```kotlin
config = CameraConfiguration(
    qualityPrioritization = QualityPrioritization.BALANCED
)
```

**Options:**
- `QualityPrioritization.QUALITY` -- Best quality, slower capture
- `QualityPrioritization.BALANCED` -- Good balance (default)
- `QualityPrioritization.SPEED` -- Fast capture, lower quality

**Use cases:**
- `QUALITY`: Professional photography, print media
- `BALANCED`: General-purpose apps
- `SPEED`: Burst mode, document scanning

## Front Camera Mirroring

Mirror front-camera captures so a selfie matches the mirrored preview ("what you see is what you get"). No effect on the back camera.

```kotlin
config = CameraConfiguration(
    cameraLens = CameraLens.FRONT,
    mirrorFrontCamera = true  // default: false
)
```

**Representation note:** iOS and the Android byte-array path bake the flip into the pixels. The Android file path (`takePictureToFile()`, the fast path) records it as an EXIF orientation tag — EXIF-aware viewers honor it, but raw-pixel consumers may ignore it.

## Logging

Internal logging is off by default. Enable it (e.g. in debug builds) or route it to your own logger via `CameraKLogger`:

```kotlin
import com.kashif.cameraK.utils.CameraKLogger

CameraKLogger.enabled = true                          // turn on internal logs
CameraKLogger.sink = { level, tag, msg, throwable ->  // optional: forward to your logger
    Log.d(tag, "[$level] $msg")
}
```

## Complete Example

```kotlin
@Composable
fun ProfessionalCameraScreen() {
    val scope = rememberCoroutineScope()

    val cameraState by rememberCameraKState(
        config = CameraConfiguration(
            // Camera hardware
            cameraLens = CameraLens.BACK,
            cameraDeviceType = CameraDeviceType.TELEPHOTO,  // iOS only

            // Image properties
            aspectRatio = AspectRatio.RATIO_4_3,
            targetResolution = 3840 to 2160,  // 4K
            imageFormat = ImageFormat.PNG,

            // Quality settings
            qualityPrioritization = QualityPrioritization.QUALITY,

            // Flash and storage
            flashMode = FlashMode.AUTO,
            directory = Directory.PICTURES,
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (cameraState) {
            is CameraKState.Ready -> {
                val ready = cameraState as CameraKState.Ready
                val controller = ready.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            when (val result = controller.takePictureToFile()) {
                                is ImageCaptureResult.SuccessWithFile -> {
                                    println("Saved 4K PNG: ${result.filePath}")
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
                val error = cameraState as CameraKState.Error
                Text("Camera error: ${error.message}")
            }

            CameraKState.Initializing -> {
                CircularProgressIndicator()
            }
        }
    }
}
```

## Runtime Configuration

Some settings can be changed after initialization:

### Zoom

```kotlin
val maxZoom = controller.getMaxZoom()  // e.g., 10.0
controller.setZoom(2.5f)  // 2.5x zoom
val currentZoom = controller.getZoom()  // returns 2.5
```

### Flash

```kotlin
controller.setFlashMode(FlashMode.ON)
controller.toggleFlashMode()  // OFF -> ON -> AUTO -> OFF
```

### Torch (Flashlight)

```kotlin
controller.toggleTorchMode()  // Toggle continuous light
```

### Camera Lens

```kotlin
controller.toggleCameraLens()  // BACK <-> FRONT
```

## Platform-Specific Notes

### Android

- CameraX handles resolution selection automatically if not specified
- Flash modes depend on device hardware
- Some devices don't support all aspect ratios

### iOS

- `CameraDeviceType` only works on devices with multiple cameras
- Resolution is constrained by selected `AVCaptureSessionPreset`
- Flash availability depends on camera type (front cameras often lack flash)

### Desktop

- Resolution depends on webcam capabilities
- Flash and torch are usually not available
- Camera switching depends on multiple webcam availability

## Configuration Reference

| Property | Type | Default | Platforms |
|--------|----------------|---------|-----------|
| `cameraLens` | `CameraLens` | `BACK` | All |
| `cameraDeviceType` | `CameraDeviceType` | `DEFAULT` | iOS only |
| `aspectRatio` | `AspectRatio` | `RATIO_16_9` | All |
| `targetResolution` | `Pair<Int, Int>` | Device default | All |
| `flashMode` | `FlashMode` | `AUTO` | Android, iOS |
| `imageFormat` | `ImageFormat` | `JPEG` | All |
| `directory` | `Directory` | `PICTURES` | All |
| `qualityPrioritization` | `QualityPrioritization` | `BALANCED` | All |
| `torchMode` | `TorchMode` | `OFF` | Android, iOS |
| `mirrorFrontCamera` | `Boolean` | `false` | Android, iOS |

## Next Steps

- [Camera Capture](../guides/camera-capture.md) -- Capture photos and handle results
- [Zoom Control](../guides/zoom-control.md) -- Implement pinch-to-zoom
- [Flash and Torch](../guides/flash-and-torch.md) -- Control lighting
