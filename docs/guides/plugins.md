# Plugins

A plugin adds a feature on top of the holder: scanning codes, reading text, encoding frames or
saving to the gallery. It sees the holder through a `CameraKPluginContext` (state, frames and
captures, but no commands), so a plugin cannot shut the camera down or change its settings.

| Artifact | Plugin | Package |
|----------|--------|---------|
| `camerak-qr-scanner` | `QRScannerPlugin` | `com.kashif.qrscannerplugin` |
| `camerak-ocr` | `OcrPlugin` | `com.kashif.ocrplugin` |
| `camerak-analyzer` | `AnalyzerPlugin` | `com.kashif.analyzerplugin` |
| `camerak-media-saver` | `MediaSaverPlugin` | `com.kashif.mediasaverplugin` |

## Using plugins

Create plugins with their `remember…` functions and pass them when the holder is created:

```kotlin
val scanner = rememberQRScannerPlugin()
val ocr = rememberOcrPlugin()
val holder = rememberCameraKStateHolder(plugins = listOf(scanner, ocr))
```

A plugin passed at creation that reads frames turns on `CameraConfiguration.frameAnalysis`, so the
first bind already carries the analysis output. A changed list detaches the plugins no longer in it
and attaches the new ones. Outside Compose, pass them to `CameraKStateHolder(config, platformContext, plugins)`,
or call `holder.attachPlugin(plugin)` and `detachPlugin(plugin)`.

Every built-in plugin has:

- `results`: a `Flow` of what it found; not replayed, so collect it while the plugin runs.
- `status`: `StateFlow<PluginStatus>`, which is `Idle`, `Running`, `Paused` or `Failed(error)`.
- `pause()` and `resume()` on the three frame readers, which stop and restart reading frames without
  touching the camera.

A plugin that fails is contained: its `status` becomes `Failed`, the holder emits
`CameraKEvent.PluginFailed(pluginId, error)` on `events`, and the camera and other plugins carry on.

## QR Scanner Plugin

```kotlin
val scanner = rememberQRScannerPlugin(
    QRScannerConfig {
        formats = setOf(BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13)
        maxFps = 5f
    },
)
val holder = rememberCameraKStateHolder(plugins = listOf(scanner))

LaunchedEffect(scanner) {
    scanner.results.collect { barcode ->
        // The payload is untrusted: check it before acting on it.
        val text = barcode.rawValue ?: return@collect
        onScanned(text)
    }
}
```

A `Barcode` has `rawValue`, `rawBytes`, `format` and `boundingBox` (in frame pixels). The same code
is reported once per `dedupeWindow` (one second by default). `region` limits decoding to a part of
the frame. ZXing decodes on Android and desktop, Vision on iOS; a format the platform cannot read is
logged once and ignored.

| `QRScannerConfig` field | Default |
|-------------------------|---------|
| `formats` | `setOf(BarcodeFormat.QR_CODE)` |
| `maxFps` | `5f` |
| `dedupeWindow` | `1.seconds` |
| `region` | `null` (the whole frame) |

## OCR Plugin

Live text from the analysis frames:

```kotlin
val ocr = rememberOcrPlugin(OcrConfig { maxFps = 2f })
val holder = rememberCameraKStateHolder(plugins = listOf(ocr))

LaunchedEffect(ocr) {
    ocr.results.collect { recognized -> showText(recognized.text) }
}
```

Text from a capture, once:

```kotlin
scope.launch {
    holder.capture().onSuccess { image ->
        ocr.recognize(image, OcrLevel.ACCURATE)
            .onSuccess { recognized -> showText(recognized.text) }
    }
}
```

`RecognizedText` has `text`, `blocks` (each `TextBlock` with its `text`, `bounds` and `confidence`)
and `timestampNanos`. `recognize` runs off the calling thread and never throws: an unreadable image
or a recognizer failure is `Failure(Internal)`. Recognized text is never logged. ML Kit reads on
Android, Vision on iOS, Tesseract on desktop.

### Desktop language data

Desktop OCR runs Tesseract, which needs `eng.traineddata`. The plugin does not ship it. It uses the first of:

1. `OcrConfig { tessdataPath = "/path/to/tessdata" }`. If that directory has no `eng.traineddata`, OCR fails; no other source replaces it.
2. The `eng.traineddata` from [tesseract-ocr/tessdata](https://github.com/tesseract-ocr/tessdata), on your classpath at `com/kashif/ocrplugin/tessdata/eng.traineddata`. It is used only if its SHA-256 matches the one pinned in the library, after it is extracted to a per-user directory.
3. The directory in the `TESSDATA_PREFIX` environment variable.

The working directory is never searched. Tesseract parses whatever model the first and third options point at, so point them only at files you trust.

## Analyzer Plugin

JPEG-encoded frames for your own processing, such as a server-side model:

```kotlin
val analyzer = rememberAnalyzerPlugin(
    AnalyzerConfig {
        maxFps = 1f
        maxDimension = 1280
        jpegQuality = 80
    },
)
val holder = rememberCameraKStateHolder(plugins = listOf(analyzer))

LaunchedEffect(analyzer) {
    analyzer.results.collect { frame -> upload(frame.bytes, frame.rotationDegrees) }
}
```

An `EncodedFrame` has the JPEG `bytes`, its `width` and `height` after any downscaling to
`maxDimension` (null keeps the frame's size), `rotationDegrees` to turn it upright, and
`timestampNanos`.

## Media Saver Plugin

Core writes captures and recordings to app-owned files only. The media saver is the one part of
CameraK that publishes to the gallery, by copying those files:

```kotlin
val saver = rememberMediaSaverPlugin(
    MediaSaverConfig {
        destination = GalleryDestination.PICTURES
        folderName = "MyApp"
    },
)
val holder = rememberCameraKStateHolder(plugins = listOf(saver))

scope.launch {
    holder.capture().onSuccess { image ->
        saver.save(image)                       // CameraKResult<SavedImage>
            .onFailure { error -> showError(error) }
    }
}
```

- `save(image: CapturedImage, name)` and `save(video: RecordedVideo, name)` copy the file unchanged
  and return `SavedImage` or `SavedVideo`, whose `asset` is the gallery's handle: the content URI on
  Android, the Photos local identifier on iOS, the file path on desktop.
- `readBytes(saved)` reads a saved image back, off the calling thread. On iOS this needs
  read-write Photos access, which the app requests itself (`NSPhotoLibraryUsageDescription`).
- With `autoSave = true` every successful `capture()` is saved, and each outcome arrives on
  `results`.
- `destination` is `PICTURES` (default), `DCIM` or `DOCUMENTS`; `folderName` defaults to
  `CameraK`, and iOS has no folders.

The saver shows no permission prompt. Each save first checks `saver.galleryPermission` (iOS, and
Android API 28 and below; null where none is needed) and fails with
`Permission(GALLERY_ADD, status)` without touching the gallery unless it is granted. Ask for it
with `rememberCameraKPermissionState(CameraKPermission.GALLERY_ADD)`. iOS also saves while the
permission is not yet determined, and Photos shows its add-only prompt during the save.

## Your own frame analysis

The holder's `frames` is the same frame source the plugins use:

```kotlin
DisposableEffect(holder) {
    val registration = holder.frames.register(FrameSpec(maxFps = 10f)) { frame ->
        val luma = ByteArray(frame.width * frame.height)
        val size = frame.copyLuma(luma)
        brightness.value = (0 until size).sumOf { luma[it].toInt() and 0xFF } / size.toDouble()
    }
    onDispose { registration.close() }
}
```

The frame is borrowed: it is valid only during the call, unless you `retain()` it and close the
`RetainedFrame` later. Frames arrive while the camera is Ready, on the registration's own worker,
latest-wins: a frame that arrives while your analyzer runs is skipped, never queued. `FrameSpec`
sets `maxFps`, `maxRetained`, `synchronous` (run on the producer thread, no copy) and
`acceptedFormats`. A registration whose session format it does not accept gets a `rejection` and no
frames. Turn on `CameraConfiguration.frameAnalysis` when you register outside a plugin, so the
first registration needs no rebind.

## Writing a plugin

```kotlin
class BrightnessPlugin : CameraKPlugin {
    private val _results = MutableSharedFlow<Double>(extraBufferCapacity = 16)
    val results: Flow<Double> = _results.asSharedFlow()

    override fun onAttach(context: CameraKPluginContext) {
        context.launchPerSession {
            var luma = ByteArray(0)
            context.frames.register(FrameSpec(maxFps = 5f)) { frame ->
                if (luma.size < frame.width * frame.height) luma = ByteArray(frame.width * frame.height)
                val size = frame.copyLuma(luma)
                _results.tryEmit((0 until size).sumOf { luma[it].toInt() and 0xFF } / size.toDouble())
            }.use { awaitCancellation() }
        }
    }

    override fun onDetach() = Unit
}
```

- `onAttach(context)` runs when the first session reaches Ready, or at once when the plugin is
  attached later. `context.scope` is cancelled at detach.
- `launchPerSession` runs its block once per Ready session and cancels it when the camera leaves
  Ready.
- The context carries `cameraState`, `uiState`, `capabilities`, `deviceOrientation`,
  `recordingState`, `frames` and `captures`, and `reportFailure(error)` to fail the plugin. It has
  no commands.
- `needsFrames` (true by default) tells the holder to bind frame analysis when the plugin is passed
  at creation; a plugin that only reads `captures` returns false. `id` names the plugin in
  `PluginFailed`.

## Next Steps

- [Testing](testing.md): test a plugin over the fake frame source
- [Video Recording](video-recording.md)
