@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import kotlin.test.assertIs
import kotlin.test.fail

/** A recording the engine started on [AppleEngineDriver.movieRecorder]: its path and its movie-output start. */
internal class EngineRecording(val path: String, val start: FakeMovieRecorder.Start)

/**
 * Starts a recording with [config] on [engine], reports AVFoundation's didStartRecording, and returns
 * once startRecording has; AVFoundation's file is created at the recording's path, so it exists as a
 * recording's file would. Fails when startRecording returns without starting the movie output.
 */
internal suspend fun TestScope.startEngineRecording(
    driver: AppleEngineDriver,
    engine: CameraEngine,
    config: VideoConfiguration = VideoConfiguration(),
): EngineRecording {
    val before = driver.movieRecorder.starts.size
    val start = async { engine.startRecording(config) }
    repeat(50) {
        if (driver.movieRecorder.starts.size > before) return@repeat
        driver.settle()
        if (driver.movieRecorder.starts.size <= before && start.isCompleted) {
            fail("startRecording returned ${start.await()} without starting the movie output")
        }
    }
    val started = driver.movieRecorder.starts.getOrNull(before) ?: fail("the recording never reached the movie output")
    NSFileManager.defaultManager.createFileAtPath(started.url.path!!, NSData(), null)
    driver.movieRecorder.reportStarted(started)
    repeat(50) { if (!start.isCompleted) driver.settle() }
    val path = assertIs<CameraKResult.Success<String>>(start.await(), "startRecording").value
    return EngineRecording(path, started)
}

/** AVFoundation's didFinishRecording for [recording] with [error], then a settle. */
internal suspend fun finishRecording(driver: AppleEngineDriver, recording: EngineRecording, error: NSError? = null) {
    driver.movieRecorder.reportFinished(recording.start, error)
    driver.settle()
}

/** The engine's recording events, recorded as they are emitted. */
internal fun TestScope.recordingEventsOf(engine: CameraEngine): Recorded<EngineRecordingEvent> =
    Recorded(backgroundScope, engine.recordingEvents)

/** The Finalized events among [events]. */
internal fun Recorded<EngineRecordingEvent>.finalized(): List<EngineRecordingEvent.Finalized> =
    values.filterIsInstance<EngineRecordingEvent.Finalized>()

/** An AVFoundation recording error with [code], and its AVErrorRecordingSuccessfullyFinishedKey. */
internal fun movieError(code: Long, successfullyFinished: Boolean): NSError = NSError.errorWithDomain(
    AVFoundationErrorDomain,
    code,
    mapOf<Any?, Any?>(AVErrorRecordingSuccessfullyFinishedKey to successfullyFinished),
)
