@file:OptIn(InternalCameraKApi::class, com.kashif.cameraK.utils.CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.toNSData
import platform.AVFoundation.AVErrorSessionNotRunning
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSThread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * On an iPhone 14 Pro (iOS 27.2) and a 14 Pro Max (iOS 26.3.1), startRunning returned in a few
 * milliseconds in about two sessions in three, and the first capture of such a session failed with
 * -11803 "Cannot Record" while the session reported running; a capture issued half a second later
 * always succeeded. A capture that fails with -11803 is issued once more, after a short delay, and
 * only while the session it was issued on still runs.
 */
class SessionNotRunningRetryTest {
    private val back = FakeDevice(isFront = false)
    private val front = FakeDevice(isFront = true)
    private val rig = CaptureRig(
        device = back,
        cameraDevices = CameraDeviceProvider { lens, _ -> if (lens == CameraLens.FRONT) front else back },
    )

    @AfterTest
    fun tearDown() = rig.tearDown()

    private fun avError(code: Long) = NSError.errorWithDomain(AVFoundationErrorDomain!!, code, null)

    private fun FakePhotoCapturer.Request.fail(code: Long) {
        callbacks.photoProcessed(null, avError(code))
        callbacks.captureFinished(avError(code))
    }

    /** Waits as long as a retry would take, and longer, servicing main meanwhile. */
    private fun waitPastTheRetryDelay() {
        spinUntil(1.5) { false }
        drainSessionThenMain(rig.custom)
    }

    @Test
    fun aCaptureTheSessionCouldNotRecordIsIssuedAgain() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorSessionNotRunning)
        awaitTrue("the capture to be issued again") { rig.capturer.requests.size == 2 || capture.result != null }
        assertEquals(null, capture.result, "the capture returned the session-not-running failure")

        val second = rig.capturer.requests[1]
        assertNotSame(first.settings, second.settings, "the retry reused settings AVFoundation has seen")
        val photo = ByteArray(32) { it.toByte() }
        second.callbacks.photoProcessed(photo.toNSData(), null)
        second.callbacks.captureFinished(null)

        val image = assertIs<CameraKResult.Success<CapturedImage>>(capture.awaitResult()).value
        assertContentEquals(photo, fileBytes(image.path), "the saved file is not the retried capture's photo")
    }

    @Test
    fun aCaptureIsIssuedAgainOnlyOnce() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorSessionNotRunning)
        awaitTrue("the capture to be issued again") { rig.capturer.requests.size == 2 || capture.result != null }
        rig.capturer.requests[1].fail(AVErrorSessionNotRunning)

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), capture.awaitResult())
        waitPastTheRetryDelay()
        assertEquals(2, rig.capturer.requests.size, "a capture was issued more than twice")
    }

    @Test
    fun anyOtherFailureIsReturnedAtOnce() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorUnknown)

        assertIs<CameraKResult.Failure>(capture.awaitResult())
        waitPastTheRetryDelay()
        assertEquals(1, rig.capturer.requests.size, "a capture that failed for another reason was issued again")
    }

    @Test
    fun closingBeforeTheRetryEndsTheCaptureAndIssuesNothing() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorSessionNotRunning)
        rig.engine.close()

        assertEquals(CameraKResult.Failure(CameraKError.Closed), capture.awaitResult())
        waitPastTheRetryDelay()
        assertEquals(1, rig.capturer.requests.size, "a capture was issued on a closed engine")
        assertEquals(0, rig.custom.outstandingPhotoDelegates, "the failed capture's delegate was kept")
    }

    @Test
    fun aCaptureIsNotIssuedAgainOnAnotherCamera() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorSessionNotRunning)
        var switched: CameraKResult<*>? = null
        rig.launch { switched = selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        awaitTrue("the switch to the front camera") { switched != null }
        assertIs<CameraKResult.Success<*>>(switched)
        assertSame(front, rig.custom.currentDevice, "the switch did not open the front camera")

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), capture.awaitResult())
        waitPastTheRetryDelay()
        assertEquals(1, rig.capturer.requests.size, "a capture asked of one camera was taken with another")
    }

    @Test
    fun aCancelledCaptureIsNotIssuedAgain() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorSessionNotRunning)
        capture.job.cancel()

        waitPastTheRetryDelay()
        assertEquals(1, rig.capturer.requests.size, "a capture nobody waits for was issued again")
        assertEquals(0, rig.custom.outstandingPhotoDelegates, "the failed capture's delegate was kept")
    }

    /**
     * capturePhotoWithSettings raises an exception Kotlin cannot catch when the photo output has no
     * active video connection, as after a switch that lost the camera; the capture is refused instead.
     */
    @Test
    fun aCaptureWithoutAnActiveVideoConnectionIsNotReady() {
        rig.start()
        rig.capturer.videoConnectionActive = false

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), rig.Capture().awaitResult())
        assertEquals(0, rig.capturer.requests.size, "a capture was handed to an output with no video connection")
    }

    @Test
    fun aRetryWithoutAnActiveVideoConnectionEndsTheCapture() {
        rig.start()
        val (capture, first) = rig.capture()
        rig.capturer.videoConnectionActive = false

        first.fail(AVErrorSessionNotRunning)

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), capture.awaitResult())
        waitPastTheRetryDelay()
        assertEquals(1, rig.capturer.requests.size, "a retry was handed to an output with no video connection")
    }

    @Test
    fun aSessionNotRunningReportedOnlyWhenTheCaptureFinishesIsRetried() {
        rig.start()
        val (capture, first) = rig.capture()

        first.callbacks.captureFinished(avError(AVErrorSessionNotRunning))
        awaitTrue("the capture to be issued again") { rig.capturer.requests.size == 2 || capture.result != null }

        assertEquals(null, capture.result, "the capture returned the session-not-running failure")
    }

    @Test
    fun aStopBeforeTheRetryEndsTheCaptureAndIssuesNothing() {
        rig.start()
        val (capture, first) = rig.capture()

        first.fail(AVErrorSessionNotRunning)
        rig.launch { stop() }
        awaitTrue("the session to stop") { !rig.session.isRunning }

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), capture.awaitResult())
        waitPastTheRetryDelay()
        assertEquals(1, rig.capturer.requests.size, "a capture was issued on a stopped session")
    }

    /**
     * Close settles the capture at once, but during a recording the session keeps its photo output and
     * runs until the movie is finalized; the retry must still see that nobody waits for the capture.
     */
    @Test
    fun closingDuringARecordingEndsTheCaptureAndIssuesNothing() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()
            val recording = startEngineRecording(driver, engine)
            val (capture, first) = captureReachingThePhotoOutput(driver, engine)

            first.callbacks.photoProcessed(null, avError(AVErrorSessionNotRunning))
            first.callbacks.captureFinished(avError(AVErrorSessionNotRunning))
            engine.close()
            driver.settle()
            assertEquals(CameraKResult.Failure(CameraKError.Closed), capture.await())

            NSThread.sleepForTimeInterval(1.0)
            driver.settle()
            assertEquals(1, driver.photoCapturer.requests.size, "a capture was issued on a closed engine")
            finishRecording(driver, recording)
            removeFiles(listOf(recording.path))
        }
    }
}
