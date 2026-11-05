@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The recorder's start timeout (startTimeoutMs) against AVFoundation's reports:
 * - a start whose timeout fires as AVFoundation reports the recording started is not abandoned: the
 *   movie output is stopped, the start waits for its end and returns Timeout(RECORD_START), and the
 *   recording, which was reported Started, still yields exactly one Finalized;
 * - a start abandoned because AVFoundation never reported it started leaves no file once
 *   AVFoundation finishes that recording after all, and the deletion is logged.
 */
class RecordingStartTimeoutTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun aStartTimingOutAsAVFoundationReportsItStartedStillYieldsOneFinalized() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val timeoutMs = driver.engine.recorder.startTimeoutMs

        val start = async { engine.startRecording(VideoConfiguration()) }
        repeat(20) { if (driver.movieRecorder.starts.isEmpty()) driver.settle() }
        driver.settle()
        val begun = driver.movieRecorder.starts.single()
        val path = begun.url.path!!.also { written += it }
        NSFileManager.defaultManager.createFileAtPath(path, NSData(), null)

        // The session queue is held, so AVFoundation's start report is queued before the block the
        // timeout queues: the start report lands at the timeout boundary.
        val release = dispatch_semaphore_create(0)
        driver.custom.afterQueuedSessionWork {
            dispatch_semaphore_wait(release, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC.toLong()))
        }
        driver.movieRecorder.reportStarted(begun)
        advanceTimeBy(timeoutMs.milliseconds)
        runCurrent()
        dispatch_semaphore_signal(release)
        repeat(20) { if (driver.movieRecorder.stops == 0) driver.settle() }

        assertEquals(
            1,
            events.values.count { it is EngineRecordingEvent.Started },
            "Started events: ${events.values}",
        )
        assertEquals(1, driver.movieRecorder.stops, "the movie output was not told to stop")
        driver.movieRecorder.reportFinished(begun, null)
        repeat(20) { if (!start.isCompleted) driver.settle() }
        driver.settle()

        assertStartTimedOut(start.await(), "the start that timed out")
        assertEquals(
            1,
            events.finalized().size,
            "Finalized events for the recording reported Started: ${events.values}",
        )
        engine.close()
    }

    @Test
    fun theFileOfAnAbandonedStartIsDeletedWhenAVFoundationFinishesItAndTheDeletionIsLogged() = driver.runCase {
        val lock = NSLock()
        val logged = mutableListOf<String>()
        val previousSink = CameraKLogger.sink
        val wasEnabled = CameraKLogger.enabled
        CameraKLogger.sink = { level, _, message, _ ->
            lock.lock()
            try {
                logged += "$level $message"
            } finally {
                lock.unlock()
            }
        }
        CameraKLogger.enabled = true
        try {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val timeoutMs = driver.engine.recorder.startTimeoutMs

            val start = async { engine.startRecording(VideoConfiguration()) }
            repeat(20) { if (driver.movieRecorder.starts.isEmpty()) driver.settle() }
            driver.settle()
            val begun = driver.movieRecorder.starts.single()
            val path = begun.url.path!!.also { written += it }
            NSFileManager.defaultManager.createFileAtPath(path, NSData(), null)

            advanceTimeBy(timeoutMs.milliseconds)
            runCurrent()
            repeat(20) { if (!start.isCompleted) driver.settle() }
            assertStartTimedOut(start.await(), "a start AVFoundation never reported")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "AVFoundation's file before it finished")

            driver.movieRecorder.reportFinished(begun, null)
            driver.settle()

            assertFalse(
                NSFileManager.defaultManager.fileExistsAtPath(path),
                "the file of the abandoned start is still on disk",
            )
            val lines = lock.run {
                lock()
                try {
                    logged.toList()
                } finally {
                    unlock()
                }
            }
            assertTrue(
                lines.any { "abandoned" in it && "deleted" in it },
                "no log line says the abandoned start's file was deleted: $lines",
            )
            engine.close()
        } finally {
            CameraKLogger.sink = previousSink
            CameraKLogger.enabled = wasEnabled
        }
    }

    private fun assertStartTimedOut(result: CameraKResult<String>, what: String) {
        val error = assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(result, what).error, what)
        assertEquals(CameraOperation.RECORD_START, error.operation, what)
    }
}
