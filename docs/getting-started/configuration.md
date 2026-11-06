# Configuration

`CameraConfiguration` is what the camera opens with. Build it with the DSL; every field you leave
out keeps its default, so `CameraConfiguration()` is the default configuration:

```kotlin
val config = CameraConfiguration {
    cameraLens = CameraLens.BACK
    aspectRatio = AspectRatio.RATIO_4_3
    flashMode = FlashMode.AUTO
    imageFormat = ImageFormat.JPEG
    qualityPrioritization = QualityPrioritization.QUALITY
}
val holder = rememberCameraKStateHolder(config)
```

The configuration is immutable: `config.copy { flashMode = FlashMode.OFF }` builds a changed copy.
A new configuration builds a new holder, which opens its camera once the old one has closed, so
keep the configuration stable (remember it, or build it from saved settings) and change the camera
at runtime with the holder's commands instead.

Every field is applied when the camera first binds. A value the platform cannot honour fails the
start with `CameraKError.Unsupported(feature)`; `holder.capabilities` lists what each camera offers.

## Fields

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `cameraLens` | `CameraLens?` | `null` | `FRONT` or `BACK`. Null is the platform default: the back camera on Android and iOS, the system default device on desktop |
| `cameraDeviceType` | `CameraDeviceType` | `DEFAULT` | `WIDE_ANGLE`, `ULTRA_WIDE`, `TELEPHOTO`, `MACRO` or `DEFAULT` |
| `cameraId` | `String?` | `null` | A `LensInfo.id` from `holder.capabilities`; wins over the lens and device type |
| `flashMode` | `FlashMode` | `OFF` | `ON`, `OFF` or `AUTO`, for captures |
| `torchMode` | `TorchMode` | `OFF` | The torch at open |
| `initialZoomRatio` | `Float` | `1f` | Clamped into the camera's range |
| `imageFormat` | `ImageFormat` | `JPEG` | `JPEG`, `PNG`, or `HEIC` where the iOS camera offers HEVC |
| `qualityPrioritization` | `QualityPrioritization` | `BALANCED` | `QUALITY`, `SPEED`, `BALANCED` or `NONE` |
| `aspectRatio` | `AspectRatio` | `RATIO_16_9` | `RATIO_4_3`, `RATIO_16_9`, `RATIO_9_16` or `RATIO_1_1` |
| `targetResolution` | `Resolution?` | `null` | The capture size to aim for, e.g. `Resolution(1920, 1080)` |
| `targetOrientation` | `DeviceOrientation?` | `null` | Fixes the orientation of captures and recordings; null follows the device |
| `outputDirectory` | `String?` | `null` | Where captures (and by default recordings) are written; null is app-owned storage |
| `mirrorFrontCamera` | `Boolean` | `false` | Mirror front-camera captures to match the preview |
| `frameAnalysis` | `Boolean` | `false` | Bind the analysis output at start; set for you when a plugin that reads frames is passed at creation |
| `analysisResolution` | `Resolution?` | `null` | The size analysis frames are asked for |
| `analysisFormat` | `FrameFormat` | `YUV_420` | `YUV_420` or `RGBA_8888` |

A `Resolution` with a zero or negative side throws `IllegalArgumentException` when the configuration
is built.

## Camera selection

```kotlin
CameraConfiguration {
    cameraLens = CameraLens.BACK
    cameraDeviceType = CameraDeviceType.ULTRA_WIDE
}
```

When no camera of that device type faces that way, another camera facing that way opens. To
pick an exact camera, read `holder.capabilities.value.allLenses` and set `cameraId`, or call
`holder.selectCamera(id)` at runtime. See [Camera Switching](../guides/camera-switching.md).

## Aspect ratio

`AspectRatio.RATIO_1_1` is costly on Android: CameraX has no square output, so CameraK binds 4:3 and
crops each photo, which decodes and re-encodes it. To avoid that, capture `RATIO_4_3` and crop the
centred square for display. `holder.capabilities.value.supportsSquareCapture` says whether a
platform takes square photos.

## Where files go

Captures and recordings are app-owned files: `outputDirectory` when set, used as given, otherwise
`filesDir/CameraK` on Android, `Documents/CameraK` on iOS and `~/Documents/CameraK` on desktop
(recordings: `~/Movies/CameraK` on macOS, `~/Videos/CameraK` elsewhere). Nothing reaches the
gallery unless you save it with `MediaSaverPlugin` (see [Plugins](../guides/plugins.md#media-saver-plugin)).

## Video

Recordings take their own `VideoConfiguration`, passed to `startRecording`:

```kotlin
val video = VideoConfiguration {
    quality = VideoQuality.FHD
    maxDuration = 60.seconds
    enableAudio = true
}
```

See [Video Recording](../guides/video-recording.md).

## Desktop camera source

On desktop, `CameraKPlatformContext(grabberFactory)` replaces how frames are grabbed, for instance
to open a stream or a test pattern; the default context opens the camera device.

## Logging

```kotlin
CameraKLogger.enabled = true
CameraKLogger.sink = { level, tag, message, throwable -> myLogger.log(level.name, "$tag: $message", throwable) }
```

CameraK is silent by default. A message names the operation and its outcome, never a path or a
recognized value.

## Runtime changes

Change the running camera with the holder's commands instead of a new configuration:

```kotlin
scope.launch {
    holder.setZoom(2f)
    holder.setFlashMode(FlashMode.ON)
    holder.setTorchMode(TorchMode.ON)
    holder.selectCamera(CameraLens.FRONT)
    holder.setTargetOrientation(DeviceOrientation.PORTRAIT)
}
```

`rememberCameraKStateHolder` saves the lens, device type, flash, torch and zoom the user chose, and
restores them after process death.

## Next Steps

- [Camera Capture](../guides/camera-capture.md)
- [Camera Switching](../guides/camera-switching.md)
- [CameraKStateHolder reference](../api/state-holder.md)
