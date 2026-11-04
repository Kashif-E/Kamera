@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.graphics.Bitmap
import android.media.ExifInterface
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.FakeRecording
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Starts [engine] on [platform]'s back camera and waits until it is Open. */
internal suspend fun TestScope.open(platform: FakeAndroidPlatform, engine: AndroidCameraEngine) {
    platform.settle(testScheduler)
    assertEquals(CameraKResult.Success(Unit), engine.start(FakeAndroidPlatform.desired), "setup: start")
    platform.settle(testScheduler)
    assertEquals(EngineState.Open, engine.state.value, "setup: the camera opened")
}

/** Every recording milestone [engine] emits from now on, collected for the rest of the test. */
internal fun TestScope.recordingEventsOf(engine: AndroidCameraEngine): List<EngineRecordingEvent> {
    val events = mutableListOf<EngineRecordingEvent>()
    backgroundScope.launch { engine.recordingEvents.collect { events += it } }
    return events
}

/** Starts a recording that CameraX confirms at once, and returns CameraX's recording and its path. */
internal suspend fun TestScope.startRecording(
    platform: FakeAndroidPlatform,
    engine: AndroidCameraEngine,
    config: VideoConfiguration = VideoConfiguration(),
): Pair<FakeRecording, String> {
    val start = async { engine.startRecording(config) }
    platform.settle(testScheduler)
    assertTrue(start.isCompleted, "setup: startRecording did not return")
    val path = assertIs<CameraKResult.Success<String>>(start.await(), "setup: startRecording").value
    return platform.backend.recordings.last() to path
}

/**
 * A real JPEG, [width] by [height], whose pixel at (x, y) is [color] of it, tagged with the EXIF
 * [orientation]. Needs Robolectric's native graphics.
 */
internal fun jpeg(
    dir: File,
    width: Int,
    height: Int,
    orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    color: (x: Int, y: Int) -> Int,
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, color(x, y))
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    if (orientation == ExifInterface.ORIENTATION_NORMAL) return bytes
    val file = File.createTempFile("fixture", ".jpg", dir)
    try {
        file.writeBytes(bytes)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    } finally {
        file.delete()
    }
}
