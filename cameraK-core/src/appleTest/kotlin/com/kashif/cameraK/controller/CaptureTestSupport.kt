@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import kotlin.test.fail

/**
 * A CameraController on a fake session, camera and photo output, started so its engine has the
 * session set up and running, and helpers to run takePictureToFile on main and finish its request.
 */
internal class CaptureRig(
    val device: FakeDevice = FakeDevice(),
    qualityPrioritization: QualityPrioritization = QualityPrioritization.NONE,
    directory: Directory = Directory.DOCUMENTS,
    imageFormat: ImageFormat = ImageFormat.JPEG,
) {
    val session = RecordingSession()
    val capturer = FakePhotoCapturer()

    /** Every orientation the preview was given, in order; the preview is oriented on main only. */
    val previewOrientations = mutableListOf<AVCaptureVideoOrientation>()
    val custom = CustomCameraController(
        qualityPrioritization = qualityPrioritization,
        sessionFactory = { session },
        cameraDevices = { _, _ -> device },
        photoCapturer = capturer,
        previewOrienting = { _, orientation -> previewOrientations += orientation },
    )
    val controller = CameraController(
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        cameraLens = CameraLens.BACK,
        imageFormat = imageFormat,
        qualityPriority = qualityPrioritization,
        directory = directory,
        cameraDeviceType = CameraDeviceType.DEFAULT,
        aspectRatio = AspectRatio.RATIO_4_3,
        targetResolution = null,
        mirrorFrontCamera = false,
        customController = custom,
    )
    private val scope = MainScope()
    private val savedFiles = mutableListOf<String>()

    fun start() {
        controller.startSession() // its own engine sets the session up and starts it, with no view
        awaitTrue("the session to start") { session.isRunning }
        awaitTrue("the engine to report Open") { controller.engine.state.value == EngineState.Open }
    }

    /** One takePictureToFile call running on main; [result] is set when it returns. */
    inner class Capture {
        var result: ImageCaptureResult? = null
        val job: Job = scope.launch { result = controller.takePictureToFile() }

        fun awaitResult(): ImageCaptureResult {
            awaitTrue("takePictureToFile to return") { result != null }
            return result!!.also { (it as? ImageCaptureResult.SuccessWithFile)?.let { s -> savedFiles += s.filePath } }
        }
    }

    /** Starts a capture and waits until its request reaches the photo output. */
    fun capture(): Pair<Capture, FakePhotoCapturer.Request> {
        val before = capturer.requests.size
        val capture = Capture()
        awaitTrue("the capture request to reach the photo output") {
            capturer.requests.size > before || capture.result != null
        }
        capture.result?.let { fail("takePictureToFile returned $it without asking the photo output for a photo") }
        return capture to capturer.requests[before]
    }

    @OptIn(ExperimentalForeignApi::class)
    fun tearDown() {
        scope.cancel()
        controller.cleanup()
        drainSessionThenMain(custom)
        savedFiles.forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
    }
}

/** The bytes of the file at [path]. */
@OptIn(ExperimentalForeignApi::class)
internal fun fileBytes(path: String): ByteArray =
    (NSData.dataWithContentsOfFile(path) ?: error("no file was written at the result's path")).toByteArray()
