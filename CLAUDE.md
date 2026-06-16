# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CameraK is a Kotlin Multiplatform (KMP) camera library for Compose Multiplatform, targeting Android (CameraX), iOS (AVFoundation), and Desktop/JVM (JavaCV). Published to Maven Central under `io.github.kashif-mehmood-km`.

## Build Commands

```bash
./gradlew check                          # Full build + formatting + tests
./gradlew spotlessApply                  # Auto-format all Kotlin code (ktlint)
./gradlew spotlessCheck                  # Check formatting without fixing
./gradlew cameraK:build                  # Build core library
./gradlew Sample:assembleDebug           # Build Android sample APK
./gradlew Sample:run                     # Run desktop sample app
./gradlew dokkaGeneratePublicationHtml   # Generate API docs
./gradlew cameraK:desktopTest            # Run JVM tests for a module
./gradlew cameraK:desktopTest --tests "com.kashif.cameraK.video.VideoTypesTest"  # Single test class
```

Tests live in each module's `commonTest` (e.g. `cameraK/src/commonTest`). They run per-target — `desktopTest` is the fastest loop; `iosSimulatorArm64Test` / `testDebugUnitTest` cover the other targets.

## Module Structure

- **cameraK/** - Core library: camera controller, state management, Compose UI, plugin interface
- **ImageSaverPlugin/** - Plugin for saving captured images to device storage
- **qrScannerPlugin/** - Plugin for real-time QR/barcode scanning
- **ocrPlugin/** - Plugin for text recognition (ML Kit / Vision / Tesseract)
- **videoRecorderPlugin/** - Plugin for video recording with pause/resume support
- **analyzerPlugin/** - Plugin for custom image analysis
- **Sample/** - Multiplatform demo app (Android, iOS, Desktop)
- **iosApp/** - iOS Xcode project for Swift integration
- **convention-plugins/** - Shared Gradle build logic (Maven publishing)

All library modules target: `androidTarget`, `jvm("desktop")`, `iosX64()`, `iosArm64()`, `iosSimulatorArm64()`.

## Architecture

**Layered design (top to bottom):**

1. **Compose UI** - `CameraKScreen`, `CameraPreviewView` (expect/actual composables)
2. **CameraKStateHolder** - Pure Kotlin reactive state manager (Layer 2). Exposes `cameraState`, `uiState`, `events` as StateFlows. Manages plugin lifecycle.
3. **CameraController** - expect/actual per platform. Handles capture, video recording, zoom, flash, torch, lens switching.
4. **Platform APIs** - CameraX (Android), AVFoundation (iOS), JavaCV (Desktop)

**Key pattern: expect/actual.** Common interfaces in `commonMain`, platform implementations in `androidMain`, `appleMain`, `desktopMain`. Files use platform suffixes: `.android.kt`, `.apple.kt`.

**Plugin system:** Plugins implement `CameraKPlugin` with `onAttach(stateHolder)` / `onDetach()`. Plugins auto-activate by observing `stateHolder.cameraState` via `pluginScope`. Each plugin module has its own commonMain/androidMain/appleMain/desktopMain source sets.

**State management:** `CameraKState` is a sealed class (`Initializing`, `Ready`, `Error`). `CameraUIState` is a data class with zoom, flash, torch, lens, format, and recording state properties (`isRecording`, `isPaused`, `recordingDurationMs`). All exposed as StateFlows - no callbacks.

## Key Conventions

- Package: `com.kashif.cameraK` (core), `com.kashif.*Plugin` or `com.kashif.*plugin` (plugins)
- Formatting: Spotless + ktlint 1.5.0 with `.editorconfig`. Composable function naming is exempt from ktlint rules.
- JVM toolchain: Java 17
- Android: minSdk 21, compileSdk 36
- iOS: deployment target 13.0, static XCFrameworks
- Compose stability: `@Stable` and `@Immutable` annotations used for optimization
- Concurrency: AtomicFU for lock-free operations, coroutines + StateFlow throughout
- `takePictureToFile()` is the recommended capture API; `takePicture()` is deprecated

## Publishing

Uses vanniktech maven-publish plugin to Maven Central (Sonatype Central Portal). Coordinates: `io.github.kashif-mehmood-km:{artifactId}:{version}`. GPG-signed releases. Publishing credentials are in `gradle.properties` (do not commit secrets).
