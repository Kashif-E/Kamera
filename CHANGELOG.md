# Changelog

All notable changes to the Kamera project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

