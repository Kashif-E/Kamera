# Camera Capture

Capture photos with type-safe result handling.

## Basic Capture

```kotlin
@Composable
fun CaptureExample() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState(config = CameraConfiguration())

    when (val state = cameraState) {
        is CameraKState.Ready -> {
            val controller = state.controller

            Button(onClick = {
                scope.launch {
                    when (val result = controller.takePictureToFile()) {
                        is ImageCaptureResult.SuccessWithFile -> {
                            println("Saved to: ${result.filePath}")
                        }
                        is ImageCaptureResult.Error -> {
                            println("Error: ${result.exception.message}")
                        }
                    }
                }
            }) {
                Text("Capture Photo")
            }
        }
        is CameraKState.Error -> Text("Camera error: ${state.message}")
        CameraKState.Initializing -> CircularProgressIndicator()
    }
}
```

## Recommended: `takePictureToFile()`

**Fast, efficient direct file capture** — saves directly to disk without memory overhead.

```kotlin
suspend fun takePictureToFile(): ImageCaptureResult
```

**Returns:**
- `ImageCaptureResult.SuccessWithFile(filePath: String)`
- `ImageCaptureResult.Error(exception: Exception)`

**Benefits:**
- Direct disk write — no ByteArray conversion
- Lower memory usage — no decode/encode cycles

**Example with error handling:**

```kotlin
scope.launch {
    try {
        when (val result = controller.takePictureToFile()) {
            is ImageCaptureResult.SuccessWithFile -> {
                val file = File(result.filePath)
                println("Photo saved: ${file.absolutePath}")
                println("File size: ${file.length()} bytes")

                // Load into image viewer
                loadImage(result.filePath)
            }
            is ImageCaptureResult.Error -> {
                showError("Capture failed: ${result.exception.message}")
            }
        }
    } catch (e: CancellationException) {
        println("Capture was cancelled")
        throw e  // Re-throw cancellation
    }
}
```

## Result Types

Sealed class ensures exhaustive pattern matching:

```kotlin
sealed class ImageCaptureResult {
    data class Success(val byteArray: ByteArray) : ImageCaptureResult()
    data class SuccessWithFile(val filePath: String) : ImageCaptureResult()
    data class Error(val exception: Exception) : ImageCaptureResult()
}
```

`takePictureToFile()` only ever returns `SuccessWithFile` or `Error` — the `Success(byteArray)` variant is never produced by it, so handling those two cases is enough:

```kotlin
when (result) {
    is ImageCaptureResult.SuccessWithFile -> {
        // File saved, path available
        val path = result.filePath
    }
    is ImageCaptureResult.Error -> {
        // Error occurred
        val error = result.exception
    }
    else -> {} // Success(byteArray) is not returned by takePictureToFile()
}
```

## Capture with UI Feedback

Show loading state during capture:

```kotlin
@Composable
fun CaptureWithFeedback() {
    val scope = rememberCoroutineScope()
    val cameraState by rememberCameraKState(config = CameraConfiguration())
    var isCapturing by remember { mutableStateOf(false) }
    var lastCapturedPath by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = cameraState) {
            is CameraKState.Ready -> {
                val controller = state.controller

                CameraPreviewView(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )

                // Capture button with loading
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            isCapturing = true
                            when (val result = controller.takePictureToFile()) {
                                is ImageCaptureResult.SuccessWithFile -> {
                                    lastCapturedPath = result.filePath
                                }
                                is ImageCaptureResult.Error -> {
                                    println("Error: ${result.exception.message}")
                                }
                            }
                            isCapturing = false
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp)
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(Icons.Default.CameraAlt, "Capture")
                    }
                }

                // Show thumbnail of last capture
                lastCapturedPath?.let { path ->
                    AsyncImage(
                        model = path,
                        contentDescription = "Last capture",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }

            is CameraKState.Error -> Text("Camera error: ${state.message}")
            CameraKState.Initializing -> CircularProgressIndicator()
        }
    }
}
```

## Burst Capture

Capture multiple photos quickly:

```kotlin
fun captureBurst(controller: CameraController, count: Int) {
    val scope = CoroutineScope(Dispatchers.Default)

    scope.launch {
        val results = mutableListOf<String>()

        repeat(count) { index ->
            when (val result = controller.takePictureToFile()) {
                is ImageCaptureResult.SuccessWithFile -> {
                    results.add(result.filePath)
                    println("Captured ${index + 1}/$count")
                }
                is ImageCaptureResult.Error -> {
                    println("Burst failed at ${index + 1}: ${result.exception.message}")
                    return@launch
                }
            }

            // Small delay between captures
            delay(100)
        }

        println("Burst complete: ${results.size} photos")
    }
}
```

**Limitations:**
- Maximum 3 concurrent captures (queued automatically)
- Rapid captures may reduce quality on some devices
- Memory pressure increases with burst count

## Capture with Flash Control

Toggle flash before capture:

```kotlin
Button(onClick = {
    scope.launch {
        // Enable flash
        controller.setFlashMode(FlashMode.ON)

        // Capture with flash
        val result = controller.takePictureToFile()

        // Disable flash
        controller.setFlashMode(FlashMode.OFF)

        when (result) {
            is ImageCaptureResult.SuccessWithFile -> {
                println("Flash photo: ${result.filePath}")
            }
            is ImageCaptureResult.Error -> {
                println("Error: ${result.exception.message}")
            }
        }
    }
}) {
    Text("Capture with Flash")
}
```

## Save to Custom Location

By default, images save to the configured directory. To customize:

```kotlin
// Configure save directory
val cameraState by rememberCameraKState(
    config = CameraConfiguration(
        directory = Directory.DCIM  // Save to Camera roll
    )
)
```

**Options:**
- `Directory.PICTURES` — User pictures (default)
- `Directory.DCIM` — Camera roll
- `Directory.DOCUMENTS` — Documents

**File naming:**
Files are automatically named with timestamp:
```
IMG_20240129_143022.jpg
```

## Error Handling

Handle common error scenarios:

```kotlin
scope.launch {
    when (val result = controller.takePictureToFile()) {
        is ImageCaptureResult.SuccessWithFile -> {
            handleSuccess(result.filePath)
        }
        is ImageCaptureResult.Error -> {
            when (result.exception) {
                is IOException -> {
                    // Storage full or permission issue
                    showError("Cannot save photo: storage issue")
                }
                is IllegalStateException -> {
                    // Camera not ready
                    showError("Camera not ready, try again")
                }
                is CancellationException -> {
                    // User cancelled
                    println("Capture cancelled")
                }
                else -> {
                    // Unknown error
                    showError("Capture failed: ${result.exception.message}")
                }
            }
        }
    }
}
```

## Common Issues

### "Camera not initialized"

**Cause:** Attempting capture before camera is ready.

**Solution:** Only capture when state is `Ready`:

```kotlin
when (cameraState) {
    is CameraKState.Ready -> {
        // Safe to capture
        controller.takePictureToFile()
    }
    else -> {
        println("Camera not ready")
    }
}
```

### "Storage permission denied" (Android)

**Cause:** Missing storage permission on Android < 10.

**Solution:** Add permission to manifest:

```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

### "Burst queue full"

**Cause:** Too many concurrent capture requests.

**Solution:** Limit to 3 concurrent captures or wait for completion:

```kotlin
scope.launch {
    val result1 = controller.takePictureToFile()
    // Wait for first to complete before next
    val result2 = controller.takePictureToFile()
}
```

## Performance Tips

1. **Use `takePictureToFile()`** — direct file capture avoids holding the full image in memory
2. **Lower resolution** — Set `targetResolution` in `CameraConfiguration` for faster capture
3. **JPEG format** — Faster than PNG
4. **Quality prioritization** — Use `QualityPrioritization.SPEED` for rapid capture
5. **Avoid burst on low-end devices** — Limit to 3-5 photos

## Next Steps

- [Flash and Torch](flash-and-torch.md) — Control camera lighting
- [Zoom Control](zoom-control.md) — Implement zoom functionality
- [CameraController API](../api/controller.md) — Full API reference
