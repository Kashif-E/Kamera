# Changelog

All notable changes to the Kamera project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1] - 2026-06-30

### Fixed
- **Android photos cropped / reduced field-of-view** (#136): captured stills were zoomed-in vs the preview (e.g. a 4080×3060 sensor saved as a 3060×2295 centre crop). Cause: CameraK always bound a 16:9 `VideoCapture` use case in the photo `UseCaseGroup`, and the shared `ViewPort` had to fit every use case — so the 16:9 video shrank the common field of view for the 4:3 still (StreamSharing masked it only when 4+ use cases were bound). `VideoCapture` is now bound lazily — only while a recording is active — so photos use the full sensor field of view. A lens/device switch or a second `startRecording()` during recording is now rejected (they would tear down or orphan the live session), and a failed recording start rolls back so later photos stay full-FOV. Verified on a Galaxy S23 across photo/record/photo-after-record.
- **Android captured photo aspect ratio & orientation** (#136, follow-up): the 1.0 fix letterboxed the preview but, on real hardware, the **saved photo** still came out wrong — e.g. a portrait `RATIO_4_3` capture saved as a cropped 4:3 *landscape*. Root cause (found via on-device testing): three orientation sources disagreed — the capture rotation/EXIF was driven by the accelerometer while the ViewPort crop and preview followed the **display** rotation. Fixes: (1) the ViewPort `Rational` is now orientation-aware (portrait `RATIO_4_3` → 3:4) and rebuilt on a portrait↔landscape rotation; (2) capture `targetRotation` now follows the display rotation — the same source as the ViewPort and preview — so all three agree (WYSIWYG). Verified on a Galaxy S23 across all four ratios × both orientations × with/without the analyzer plugin (StreamSharing).
- **Android rotation during recording** (#147): a portrait↔landscape flip mid-recording no longer tears down the active recording. The ViewPort rebuild is deferred and applied when the recording finalizes; a recording that finalizes on error also clears its state so later rotations rebind correctly. `setTargetOrientation(null)` now reverts to the display rotation (consistent with auto mode) instead of a possibly-stale sensor orientation.

### Changed (behavior)
- **`targetResolution` no longer overrides `aspectRatio`** (Android): when both are set, the aspect ratio is the primary constraint and the resolution is the preferred size within it. Previously a target like `1920×1080` forced 16:9 output even when `RATIO_4_3` was requested.
- **Capture orientation in display-locked apps** (Android): an activity locked to one orientation now produces photos in that orientation (matching its preview) rather than sensor-rotated stills. Apps wanting sensor-based rotation should leave the activity orientation unlocked or use `setTargetOrientation()`.

## [1.0] - 2026-06-19

### Added
- **Opt-in logging (`CameraKLogger`)** (#133): internal logging is now disabled by default and routed through `CameraKLogger`. Set `CameraKLogger.enabled = true` to turn it on, or provide a custom `CameraKLogger.sink` to forward logs to your own logger.
- **`mirrorFrontCamera` configuration** (#112): optionally mirror front-camera captures to match the mirrored preview. Defaults to `false`. iOS and the Android byte-array path bake the flip into pixels; the Android file path records it as an EXIF orientation tag.
- **CI build check** (#129): a workflow builds all targets on every PR; merges are gated on passing checks and resolved review conversations.

### Changed
- **Better barcode/QR detection** (#130) on Android and iOS (stride-aware luminance, `TRY_HARDER`, inverted retry).
- **Android analyzer** now emits decodable JPEG frames (matching iOS) and runs off the main thread, fixing frame backpressure and `Could not decode ByteArray to Bitmap` consumers.
- **`setPreferredCameraDeviceType()`** now performs a live re-bind instead of requiring reinitialization.

### Removed (breaking)
- Deprecated `CameraController.takePicture()` (returned `ByteArray`) — use `takePictureToFile()`.
- Legacy callback-based `CameraPreview` API and the new/old API toggle.
- Dead `returnFilePath` flag and other removed-API references in docs. See the migration table in the README.

### Fixed
- **Plugin lifecycle leaks**: plugins could not remove what they registered, so listeners/outputs/analyzers accumulated on every camera re-init (lens switch, recompose), and coroutines/outputs leaked past detach. Fixes:
  - **Core**: added `CameraController.removeImageCaptureListener(...)` and iOS `safeRemoveOutput(...)` / `clearMetadataObjectsDelegate()`; `pluginScope` is now a StateHolder-owned child scope that `shutdown()` actually cancels (the old doc claimed this but never did); `attachedPlugins` access is synchronized and ignores duplicate attaches; `getReadyCameraController()` returns null on camera error instead of hanging forever.
  - **ImageSaverPlugin**: no longer double-saves after a re-attach (single listener, re-registered per Ready, removed on detach; saves run on `pluginScope`).
  - **AnalyzerPlugin / QRScannerPlugin / OcrPlugin**: each setup now returns a teardown handle; the plugin removes the prior analyzer/output before re-registering on a new Ready and on detach (Android `unregisterImageAnalyzer`, iOS `safeRemoveOutput` / cleared delegate, desktop cancels the collector scope) instead of stacking one per Ready. Frame/result flows are buffered (`DROP_OLDEST`) instead of spawning a coroutine per frame; `OcrPlugin.ocrFlow` is now a buffered channel that isn't closed on detach (so re-attach works) and OCR releases its ML Kit / Tesseract resources.
  - **VideoRecorderPlugin**: `startRecording()` while already recording is rejected instead of orphaning the timer/recorder; `shutdown()` now detaches plugins before resetting recording state so an in-flight recording is stopped (and emits `RecordingStopped`) before teardown; Android `stopRecording()` no longer hangs forever if the finalize event never arrives (5s timeout). iOS pause/resume and desktop pause limitations are now documented in code.
  - **Desktop `saveImage`**: writes to a real per-user directory (`~/Pictures` / `~/Documents`, honoring `customFolderName`) and respects `imageFormat`, instead of a relative dir literally named `PICTURES`; failures are logged and reported instead of silently swallowed.
- **iOS underexposed/black photos** (#138): `MemoryManager` compared `physicalMemory` to itself, so it reported 100% usage and `isUnderMemoryPressure()` latched on permanently. That made every capture downshift the session preset (`adjustSessionQuality`) synchronously right before `capturePhotoWithSettings`, capturing the still mid-reconfiguration before auto-exposure re-converged. Most visible on front-camera selfies; masked when a plugin (OCR/QR/Analyzer) kept an `AVCaptureVideoDataOutput` streaming. Memory pressure is now driven by the system memory-warning notification, and the capture no longer reconfigures the preset at shot time.
- **Android aspect ratio mismatch** (#136): a configured ratio (e.g. `RATIO_4_3`) no longer produces a photo that's a crop of the full-screen (≈16:9) field of view. The preview is now letterboxed to the configured ratio (`FIT_CENTER`) so its shared CameraX `ViewPort` matches the capture — preview FOV equals captured FOV. Adds `CameraController.getAspectRatio()`.
- **iOS preview/capture mismatch on flat devices** (#115, #109): preview no longer distorts when the device is face-up.
- **iOS crash from deprecated video stabilization API** (#113).
- **Android auto-save after capture**: capture listeners are now fired on `takePictureToFile()`.
- **Cross-analyzer buffer corruption**: the shared `ImageProxy` Y-plane buffer is duplicated before reads so concurrent analyzers aren't affected.
- **Desktop `takePictureToFile()`** now writes a real file instead of returning an empty path.
- **`ImageSaverPlugin.getByteArrayFrom()`** (Android) now reads raw file paths before falling back to `ContentResolver`.
- **Plugin initialization timing** (#52): null checks and graceful failure when the camera isn't ready yet.
- **Desktop Tesseract path resolution** (#51): dynamic tessdata lookup with graceful degradation.
- **Desktop QR scanner** (#50): fixed malformed decode-hints `EnumMap` initialization.
- **iOS image orientation** (#44): captures save in the correct orientation across portrait, landscape, and upside-down.

