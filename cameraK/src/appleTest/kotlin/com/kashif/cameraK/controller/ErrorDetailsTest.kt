package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVErrorApplicationIsNotAuthorizedToUseDevice
import platform.AVFoundation.AVErrorSessionNotRunning
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSLock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The NSError carried somewhere in this throwable's cause chain. */
internal fun Throwable.platformError(): NSError? =
    generateSequence(this) { it.cause }.filterIsInstance<NSErrorException>().firstOrNull()?.error

internal fun describedAVError(code: Long, description: String) = NSError.errorWithDomain(
    AVFoundationErrorDomain,
    code,
    mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description),
)

/**
 * An error CameraK reports keeps what the platform said: its own message says what failed, and the
 * NSError (domain, code, description) travels as the cause. An exception whose message never
 * reaches Exception, or an NSError dropped where the error is made, leaves the app with nothing to
 * tell a camera the user has not authorised from one that does not exist.
 */
class ErrorDetailsTest {
    private val rigs = mutableListOf<CaptureRig>()

    @AfterTest
    fun tearDown() = rigs.forEach { it.tearDown() }

    private class Reported {
        private val error = atomic<CustomCameraController.CameraException?>(null)

        fun record(e: CustomCameraController.CameraException) {
            error.compareAndSet(null, e)
        }

        fun await(): CustomCameraController.CameraException {
            awaitTrue("onError") { error.value != null }
            return error.value!!
        }
    }

    private fun controller(
        device: FakeDevice = FakeDevice(),
        capturer: FakePhotoCapturer = FakePhotoCapturer(),
        session: RecordingSession = RecordingSession(),
        reported: Reported,
    ): CustomCameraController = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { session },
        cameraDevices = { _, _ -> device },
        photoCapturer = capturer,
    ).also { it.onError = reported::record }

    private fun CustomCameraController.setUpAndStart(session: RecordingSession) {
        val ready = atomic(false)
        onSessionReady = { ready.value = true }
        setupSession()
        awaitTrue("onSessionReady") { ready.value }
        startSession()
        awaitTrue("the session to start") { session.isRunning }
    }

    @Test
    fun aCameraAVFoundationWillNotOpenIsReportedWithAVFoundationsError() {
        val denied = describedAVError(AVErrorApplicationIsNotAuthorizedToUseDevice, "Cannot use Back Camera")
        val reported = Reported()
        controller(device = FakeDevice(inputError = denied), reported = reported).setupSession()

        val error = reported.await()

        assertIs<CustomCameraController.CameraException.ConfigurationError>(
            error,
            "a camera AVFoundation refused to open was reported as a missing camera: $error",
        )
        assertSame(denied, error.platformError(), "the error does not carry AVFoundation's NSError: $error")
        val message = error.message.orEmpty()
        assertTrue(
            AVFoundationErrorDomain!! in message && "$AVErrorApplicationIsNotAuthorizedToUseDevice" in message,
            "the message does not name the domain and code: '$message'",
        )
    }

    @Test
    fun aRefusedConfigurationLockIsReportedWithWhatItWasForAndWhy() {
        val session = RecordingSession()
        val reported = Reported()
        val controller = controller(device = FakeDevice(lockSucceeds = false), session = session, reported = reported)
        controller.setUpAndStart(session)

        controller.setTorchMode(AVCaptureTorchModeOn)
        val error = reported.await()

        assertIs<CustomCameraController.CameraException.ConfigurationError>(error)
        assertTrue("torch" in error.message.orEmpty(), "the message does not say what the lock was for: $error")
        assertEquals(
            "the device refused its configuration lock",
            error.cause?.message,
            "the error does not carry why the lock was refused",
        )
    }

    @Test
    fun aFailedCaptureImageReportsAVFoundationsError() {
        val session = RecordingSession()
        val capturer = FakePhotoCapturer()
        val reported = Reported()
        val controller = controller(capturer = capturer, session = session, reported = reported)
        controller.setUpAndStart(session)
        val failure = describedAVError(AVErrorUnknown, "The operation could not be completed")

        controller.captureImage()
        awaitTrue("the capture request to reach the photo output") { capturer.requests.isNotEmpty() }
        capturer.requests.single().callbacks.photoProcessed(null, failure)
        val error = reported.await()

        assertIs<CustomCameraController.CameraException.CaptureError>(error)
        assertSame(failure, error.platformError(), "the error does not carry AVFoundation's NSError: $error")
        val message = error.message.orEmpty()
        assertTrue(
            AVFoundationErrorDomain!! in message && "$AVErrorUnknown" in message,
            "the message does not name the domain and code: '$message'",
        )
    }

    @Test
    fun aPhotoOutputThatCannotPrepareIsReportedWithAVFoundationsError() {
        val session = RecordingSession()
        val failure = describedAVError(AVErrorSessionNotRunning, "Cannot record")
        val capturer = FakePhotoCapturer().apply { prepareError = failure }
        val reported = Reported()

        controller(capturer = capturer, session = session, reported = reported).setUpAndStart(session)
        val error = reported.await()

        assertIs<CustomCameraController.CameraException.ConfigurationError>(error)
        assertSame(failure, error.platformError(), "the error does not carry AVFoundation's NSError: $error")
    }

    @Test
    fun aFailedTakePictureToFileKeepsAVFoundationsErrorAsItsCause() {
        val rig = CaptureRig().also(rigs::add)
        rig.start()
        val failure = describedAVError(AVErrorUnknown, "The operation could not be completed")

        val (capture, request) = rig.capture()
        request.callbacks.photoProcessed(null, failure)
        request.callbacks.captureFinished(failure)

        val result = assertIs<ImageCaptureResult.Error>(capture.awaitResult())
        assertSame(failure, result.exception.platformError(), "the error does not carry AVFoundation's NSError")
    }

    @Test
    fun cameraControllerLogsACameraErrorWithTheErrorItself() {
        val device = FakeDevice()
        val rig = CaptureRig(device = device).also(rigs::add)
        rig.start()
        val lock = NSLock()
        val logged = mutableListOf<Throwable?>()
        val previousSink = CameraKLogger.sink
        val wasEnabled = CameraKLogger.enabled
        CameraKLogger.sink = { level, _, _, throwable ->
            if (level == CameraKLogger.Level.ERROR) {
                lock.lock()
                try {
                    logged += throwable
                } finally {
                    lock.unlock()
                }
            }
        }
        CameraKLogger.enabled = true
        try {
            device.lockSucceeds = false
            rig.controller.setTorchMode(TorchMode.ON)
            drainSessionThenMain(rig.custom)
            awaitTrue("CameraController to log the camera's error") {
                lock.lock()
                try {
                    logged.any { it is CustomCameraController.CameraException }
                } finally {
                    lock.unlock()
                }
            }
        } finally {
            CameraKLogger.sink = previousSink
            CameraKLogger.enabled = wasEnabled
        }
    }
}
