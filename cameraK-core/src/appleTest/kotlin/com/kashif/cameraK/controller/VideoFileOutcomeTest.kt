@file:OptIn(com.kashif.cameraK.utils.CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorMaximumDurationReached
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.AVFoundation.AVErrorSessionWasInterrupted
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVFoundationErrorDomain
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A recording's outcome is what AVFoundation says it is: a recording it reports as not successfully
 * finished is an error and its file is deleted, one it reports as playable is a success whatever ended
 * it, and a full disk keeps a playable file's path (design §5's partial-file rule). Videos stay in
 * the app-owned Documents/CameraK by default, which iOS does not purge; the movie output keeps disk space free and
 * stops at the configured maximum duration. Core no longer imports recordings into Photos.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoFileOutcomeTest {
    private var rig = CaptureRig()
    private val recorder = FakeMovieRecorder()
    private val scope = MainScope()
    private val written = mutableListOf<String>()
    private val engineDriver = AppleEngineDriver()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        rig.tearDown()
        written.forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
    }

    private fun <T> run(what: String, block: suspend () -> T): T {
        var outcome: Result<T>? = null
        scope.launch {
            outcome = try {
                Result.success(block())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        awaitTrue(what) { outcome != null }
        return outcome!!.getOrThrow()
    }

    /** Starts a recording, reports it started, and writes some bytes where AVFoundation would. */
    private fun startedRecording(
        config: VideoConfiguration = VideoConfiguration { enableAudio = false },
    ): FakeMovieRecorder.Start {
        rig.engine.movieRecorder = recorder
        rig.start()
        scope.launch { rig.engine.startRecording(config) }
        awaitTrue("the recording to start on the movie output") { recorder.starts.isNotEmpty() }
        val started = recorder.starts.single()
        recorder.reportStarted(started)
        val path = started.url.path!!
        NSFileManager.defaultManager.createFileAtPath(path, NSData(), null)
        written += path
        // Let the clock move on, so the recording has a duration of at least a millisecond.
        val t0 = NSDate().timeIntervalSince1970
        awaitTrue("a millisecond to pass") { NSDate().timeIntervalSince1970 - t0 >= 0.002 }
        return started
    }

    private fun avError(code: Long, successfullyFinished: Boolean) = NSError.errorWithDomain(
        AVFoundationErrorDomain,
        code,
        mapOf<Any?, Any?>(AVErrorRecordingSuccessfullyFinishedKey to successfullyFinished),
    )

    private fun stop(started: FakeMovieRecorder.Start, error: NSError?): CameraKResult<RecordedVideo> {
        var result: CameraKResult<RecordedVideo>? = null
        scope.launch { result = rig.engine.stopRecording() }
        awaitTrue("stopRecording to ask the movie output to stop") { recorder.stops > 0 }
        recorder.reportFinished(started, error)
        awaitTrue("stopRecording to return") { result != null }
        return result!!
    }

    /** Guard: AVFoundation's playable verdict decides, not the presence of an error (green before the fix). */
    @Test
    fun aRecordingAVFoundationReportsAsPlayableIsASuccess() {
        val started = startedRecording()

        val result = stop(started, avError(AVErrorMaximumDurationReached, successfullyFinished = true))

        val success =
            assertIs<CameraKResult.Success<RecordedVideo>>(result, "a playable recording was reported as failed")
        assertEquals(started.url.path, success.value.path)
    }

    /** A full disk that kept a playable file is Storage(FULL) naming the kept file (design §5). */
    @Test
    fun aFullDiskThatKeptAPlayableFileNamesTheKeptFile() {
        val started = startedRecording()

        val result = stop(started, avError(AVErrorDiskFull, successfullyFinished = true))

        val full = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(result, "result: $result").error)
        assertEquals(StorageFailure.FULL, full.kind)
        assertEquals(started.url.path, full.keptFilePath, "the kept recording's path")
    }

    @Test
    fun documentsVideosAreKeptUnderDocumentsCameraK() {
        val started = startedRecording()

        assertTrue(
            started.url.path!!.contains("/Documents/CameraK/"),
            "a video is recorded where iOS may purge it: ${started.url.lastPathComponent}",
        )
    }

    @Test
    fun theMovieOutputKeepsDiskSpaceFreeAndStopsAtTheConfiguredMaximumDuration() {
        val started = startedRecording(
            VideoConfiguration {
                enableAudio = false
                maxDuration = 5.seconds
            },
        )

        assertTrue(started.output.minFreeDiskSpaceLimit > 0, "the movie output keeps no disk space free")
        assertEquals(5.0, CMTimeGetSeconds(started.output.maxRecordedDuration), "the maximum duration was not set")
    }

    @Test
    fun aFailedRecordingKeepsAVFoundationsErrorAsItsCause() {
        val started = startedRecording()
        val failure = avError(AVErrorDiskFull, successfullyFinished = false)

        val result = stop(started, failure)

        val error = assertIs<CameraKResult.Failure>(result, "a recording AVFoundation failed was reported as $result")
        val cause = assertNotNull(error.error.cause, "the error has no cause")
        assertSame(failure, cause.platformError(), "the error does not carry AVFoundation's NSError")
    }

    @Test
    fun anOutputDirectoryThatCannotBeCreatedFailsTheStartWithTheFileSystemsError() {
        rig.engine.movieRecorder = recorder
        rig.engine.recorder.startTimeoutMs = 300
        rig.start()
        // A directory cannot be made inside a regular file.
        val blocker = "${NSTemporaryDirectory()}cameraK-not-a-directory-${NSUUID().UUIDString}"
        NSFileManager.defaultManager.createFileAtPath(blocker, NSData(), null)
        written += blocker

        val result = run("startRecording to return") {
            rig.engine.startRecording(
                VideoConfiguration {
                    enableAudio = false
                    outputDirectory = "$blocker/videos"
                },
            )
        }

        assertEquals(emptyList(), recorder.starts.map { it.url.path }, "recorded into a directory that does not exist")
        val error = assertIs<CameraKResult.Failure>(result, "startRecording succeeded without its output directory")
        val thrown = assertNotNull(error.error.cause, "the error has no cause: ${error.error}")
        val cause = assertNotNull(thrown.platformError(), "the error does not carry the file system's NSError: $thrown")
        assertEquals(NSCocoaErrorDomain, cause.domain)
    }

    @Test
    fun unplayableFileIsDeletedAndFailure() = engineDriver.runCase {
        val engine = engineDriver.createEngine()
        engineDriver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val recording = startEngineRecording(engineDriver, engine).also { written += it.path }

        finishRecording(engineDriver, recording, avError(AVErrorUnknown, successfullyFinished = false))

        val finalized = events.finalized().single()
        assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(finalized.result).error)
        assertEquals(RecordingEndReason.ERROR, finalized.reason)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(recording.path), "the unplayable file was kept")
        engine.close()
    }

    @Test
    fun playableInterruptedIsSuccessInterrupted() = engineDriver.runCase {
        val engine = engineDriver.createEngine()
        engineDriver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val recording = startEngineRecording(engineDriver, engine).also { written += it.path }

        finishRecording(engineDriver, recording, avError(AVErrorSessionWasInterrupted, successfullyFinished = true))

        val finalized = events.finalized().single()
        assertEquals(recording.path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
        assertEquals(RecordingEndReason.INTERRUPTED, finalized.reason)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(recording.path), "the playable file was deleted")
        engine.close()
    }

    @Test
    fun diskFullKeepsPlayableFile() = engineDriver.runCase {
        val engine = engineDriver.createEngine()
        engineDriver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val recording = startEngineRecording(engineDriver, engine).also { written += it.path }

        finishRecording(engineDriver, recording, avError(AVErrorDiskFull, successfullyFinished = true))

        val finalized = events.finalized().single()
        val full = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(finalized.result).error)
        assertEquals(StorageFailure.FULL, full.kind)
        assertEquals(recording.path, full.keptFilePath, "the playable file's path")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(recording.path), "the playable file was deleted")
        engine.close()
    }
}
