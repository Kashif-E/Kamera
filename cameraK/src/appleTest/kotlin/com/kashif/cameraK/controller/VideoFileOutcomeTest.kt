package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorMaximumDurationReached
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.AVFoundation.AVFoundationErrorDomain
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recording's outcome is what AVFoundation says it is: a recording it reports as not successfully
 * finished (disk full, interrupted unplayable) is an error, one it reports as playable is a success.
 * DOCUMENTS videos stay in Documents/CameraK, which iOS does not purge; the movie output keeps disk
 * space free and stops at the configured maximum duration; a failed Photos import is an error that
 * keeps the file.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoFileOutcomeTest {
    private var rig = CaptureRig()
    private val recorder = FakeMovieRecorder()
    private val scope = MainScope()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        rig.tearDown()
        written.forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
    }

    private fun use(directory: Directory) {
        rig = CaptureRig(directory = directory)
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
        config: VideoConfiguration = VideoConfiguration(enableAudio = false),
    ): FakeMovieRecorder.Start {
        rig.controller.movieRecorder = recorder
        rig.start()
        scope.launch { rig.controller.startRecording(config) }
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

    private fun stop(started: FakeMovieRecorder.Start, error: NSError?): VideoCaptureResult {
        var result: VideoCaptureResult? = null
        scope.launch { result = rig.controller.stopRecording() }
        awaitTrue("stopRecording to ask the movie output to stop") { recorder.stops > 0 }
        recorder.reportFinished(started, error)
        awaitTrue("stopRecording to return") { result != null }
        return result!!
    }

    @Test
    fun aRecordingAVFoundationReportsAsFailedIsAnErrorAndKeepsItsFile() {
        use(Directory.DOCUMENTS)
        val started = startedRecording()

        val result = stop(started, avError(AVErrorDiskFull, successfullyFinished = false))

        assertIs<VideoCaptureResult.Error>(result, "a recording AVFoundation reported as failed was a success: $result")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(started.url.path!!), "the partial file was deleted")
    }

    /** Guard: AVFoundation's playable verdict decides, not the presence of an error (green before the fix). */
    @Test
    fun aRecordingAVFoundationReportsAsPlayableIsASuccess() {
        use(Directory.DOCUMENTS)
        val started = startedRecording()

        val result = stop(started, avError(AVErrorMaximumDurationReached, successfullyFinished = true))

        val success = assertIs<VideoCaptureResult.Success>(result, "a playable recording was reported as failed")
        assertEquals(started.url.path, success.filePath)
    }

    @Test
    fun documentsVideosAreKeptUnderDocumentsCameraK() {
        use(Directory.DOCUMENTS)
        val started = startedRecording()

        assertTrue(
            started.url.path!!.contains("/Documents/CameraK/"),
            "a DOCUMENTS video is recorded where iOS may purge it: ${started.url.lastPathComponent}",
        )
    }

    @Test
    fun theMovieOutputKeepsDiskSpaceFreeAndStopsAtTheConfiguredMaximumDuration() {
        use(Directory.DOCUMENTS)
        val started = startedRecording(VideoConfiguration(enableAudio = false, maxDurationMs = 5_000))

        assertTrue(started.output.minFreeDiskSpaceLimit > 0, "the movie output keeps no disk space free")
        assertEquals(5.0, CMTimeGetSeconds(started.output.maxRecordedDuration), "the maximum duration was not set")
    }

    @Test
    fun aFailedPhotosImportIsAnErrorAndKeepsTheFile() {
        use(Directory.PICTURES)
        rig.controller.photosLibrary = FakePhotosLibrary(FakePhotosLibrary.Answer.Failed(photosError()))
        val started = startedRecording()

        val result = stop(started, error = null)

        assertIs<VideoCaptureResult.Error>(result, "a video Photos refused was reported as $result")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(started.url.path!!), "the video was deleted")
    }
}
