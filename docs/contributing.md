# Contributing to CameraK

Thank you for your interest in contributing to CameraK! This document provides guidelines and steps for contributing.

## Ways to Contribute

- **Report Bugs** -- Found an issue? Let us know
- **Suggest Features** -- Have an idea? Share it
- **Improve Documentation** -- Fix typos, add examples, clarify
- **Submit Pull Requests** -- Fix bugs, add features
- **Help Others** -- Answer questions in Discussions

## Reporting Issues

Before creating an issue, please:

1. **Search existing issues** to avoid duplicates
2. **Use the issue template** if available
3. **Provide details**:
   - CameraK version
   - Platform (Android/iOS/Desktop) and OS version
   - Device/emulator details
   - Minimal reproducible example
   - Expected vs actual behavior
   - Stack trace/error messages

### Good Issue Example

```markdown
**CameraK Version:** 0.3
**Platform:** Android 13 (Pixel 6)

**Description:**
Camera preview shows black screen when using RATIO_1_1 aspect ratio.

**Steps to Reproduce:**
1. Configure camera with `aspectRatio = AspectRatio.RATIO_1_1`
2. Launch camera screen
3. Preview displays as black screen

**Expected:** Square camera preview displays
**Actual:** Black screen

**Code:**
```kotlin
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        aspectRatio = AspectRatio.RATIO_1_1
    )
)
```

**Error Log:**
```
E/CameraK: Failed to bind camera...
```
```

## Development Setup

### Prerequisites

- JDK 11+
- Android Studio Hedgehog or later
- Xcode 14+ (for iOS development)
- Kotlin 1.9.0+

### Clone Repository

```bash
git clone https://github.com/Kashif-E/CameraK.git
cd CameraK
```

### Build Project

```bash
./gradlew build
```

### Run Sample App

**Android:**
```bash
./gradlew :Sample:installDebug
```

**Desktop:**
```bash
./gradlew :Sample:run
```

**iOS:**
```bash
cd iosApp
pod install
open iosApp.xcworkspace
```

## Code Guidelines

### Kotlin Style

Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html):

- Use 4 spaces for indentation
- Use camelCase for functions and variables
- Use PascalCase for classes
- Maximum line length: 120 characters

### Naming Conventions

**Critical: Follow SDK naming rules**

- Methods: `[Verb][Object]` pattern
  - `captureImage()`, `setZoom()`, `getFlashMode()`
  - Not: `imageCapturer()`, `zoomSet()`, `flashModeGet()`

- Classes: Singular nouns
  - `CameraController`, `ImageCaptureResult`
  - Not: `Controllers`, `ImageCaptureResults`

- Packages: Domain-based
  - `com.kashif.cameraK.controller`
  - Not: `com.kashif.cameraK.utils`

### Documentation

**All public APIs must have KDoc:**

```kotlin
/**
 * Captures an image and saves it directly to a file.
 *
 * This method is significantly faster than takePicture() as it:
 * - Saves directly to disk without ByteArray conversion
 * - Skips decode/encode cycles (2-3 seconds faster)
 * - Avoids memory overhead from ByteArray processing
 *
 * @return ImageCaptureResult.SuccessWithFile containing the file path, or an error result
 *
 * @throws IOException if file cannot be written
 *
 * @example
 * ```kotlin
 * when (val result = controller.takePictureToFile()) {
 *     is ImageCaptureResult.SuccessWithFile -> println("Saved: ${result.filePath}")
 *     is ImageCaptureResult.Error -> println("Error: ${result.exception.message}")
 * }
 * ```
 */
suspend fun takePictureToFile(): ImageCaptureResult
```

### Error Handling

**Use sealed classes for expected errors:**

```kotlin
sealed class ImageCaptureResult {
    data class SuccessWithFile(val filePath: String) : ImageCaptureResult()
    data class Error(val exception: Exception) : ImageCaptureResult()
}
```

**Use specific exceptions for unexpected errors:**

```kotlin
class CameraNotInitializedException(message: String) : Exception(message)
```

### Testing

Add tests for new features:

```kotlin
@Test
fun `takePictureToFile returns success with file path`() = runTest {
    val controller = createTestController()
    val result = controller.takePictureToFile()

    assertTrue(result is ImageCaptureResult.SuccessWithFile)
    assertNotNull((result as ImageCaptureResult.SuccessWithFile).filePath)
}
```

## Pull Request Process

### 1. Fork and Branch

```bash
git checkout -b feature/your-feature-name
# or
git checkout -b fix/your-bug-fix
```

### 2. Make Changes

- Write clear, focused commits
- Follow code guidelines
- Add tests for new features
- Update documentation

### 3. Commit Messages

Use conventional commits:

```
feat: add pinch-to-zoom gesture support
fix: resolve camera preview freeze on rotation
docs: update installation guide for iOS
test: add zoom control unit tests
refactor: simplify flash mode toggle logic
```

### 4. Push and Create PR

```bash
git push origin feature/your-feature-name
```

Create pull request on GitHub with:

- **Title**: Clear, concise description
- **Description**:
  - What changed
  - Why (reference issue if applicable)
  - How to test
  - Screenshots/videos if UI change
- **Checklist**:
  - [ ] Code follows style guidelines
  - [ ] Documentation updated
  - [ ] Tests added/updated
  - [ ] All tests pass
  - [ ] No breaking changes (or documented)

### 5. Code Review

- Address reviewer feedback
- Keep discussion respectful
- Update PR as needed

### 6. Merge

Once approved, maintainers will merge your PR.

## Architecture Guidelines

### Layer 1: Platform-Specific (expect/actual)

```kotlin
// commonMain
expect class CameraController {
    suspend fun takePictureToFile(): ImageCaptureResult
}

// androidMain
actual class CameraController {
    actual suspend fun takePictureToFile(): ImageCaptureResult {
        // Android-specific CameraX implementation
    }
}
```

### Layer 2: State Management

```kotlin
@Stable
class CameraKStateHolder(
    private val controllerFactory: suspend () -> CameraController,
    private val coroutineScope: CoroutineScope
) {
    val cameraState: StateFlow<CameraKState>
    val uiState: StateFlow<CameraUIState>
}
```

### Layer 3: Compose UI

```kotlin
@Composable
fun rememberCameraKState(...): State<CameraKState>
```

## Breaking Changes

Avoid breaking changes when possible. If necessary:

1. **Deprecate first** (12-month timeline)
2. **Provide migration path**
3. **Document in CHANGELOG**
4. **Bump major version**

```kotlin
@Deprecated(
    message = "Use takePictureToFile() instead",
    replaceWith = ReplaceWith("takePictureToFile()"),
    level = DeprecationLevel.WARNING  // v1.x
)
suspend fun takePicture(): ImageCaptureResult
```

## Documentation Changes

Update documentation in `docs/`:

- `getting-started/` -- Installation, quick start, configuration
- `guides/` -- Feature-specific guides
- `api/` -- API reference
- `examples/` -- Platform-specific examples

Use Stripe-style documentation:
- Clear, concise examples
- Real, working code
- Copy-paste ready
- Progressive disclosure (simple -> advanced)

## Community Guidelines

- Be respectful and inclusive
- Help others learn
- Provide constructive feedback
- Follow the Code of Conduct

## Recognition

Contributors are recognized in:
- GitHub contributors page
- Release notes (for significant contributions)
- CHANGELOG (for bug fixes and features)

## Questions?

- **GitHub Discussions**: [Discussions](https://github.com/Kashif-E/CameraK/discussions)
- **Issues**: [Issues](https://github.com/Kashif-E/CameraK/issues)

## Thank You!

Your contributions make CameraK better for everyone. We appreciate your time and effort!
