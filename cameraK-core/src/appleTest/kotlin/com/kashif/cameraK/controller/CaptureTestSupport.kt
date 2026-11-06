@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
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
 * An [AppleCameraEngine] on a fake session, camera and photo output, built over its
 * [CustomCameraController] as the contract driver builds one, started so the session is set up
 * and running, and helpers to run `capture()` on main and finish its request. Captures go to
 * [outputDirectory], or to the app-owned default when it is null.
 */
internal class CaptureRig(
    val device: FakeDevice = FakeDevice(),
    qualityPrioritization: QualityPrioritization = QualityPrioritization.NONE,
    imageFormat: ImageFormat = ImageFormat.JPEG,
    outputDirectory: String? = null,
    hasUsageDescription: (String) -> Boolean = { true },
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
    val engine = AppleCameraEngine(
        CameraConfiguration {
            this.qualityPrioritization = qualityPrioritization
            this.imageFormat = imageFormat
            aspectRatio = AspectRatio.RATIO_4_3
            this.outputDirectory = outputDirectory
        },
        CameraKEnvironment(),
        custom,
        hasUsageDescription = hasUsageDescription,
    )
    private val scope = MainScope()
    private val savedFiles = mutableListOf<String>()

    /** Runs [block] on main, in the rig's scope, which [tearDown] cancels. */
    fun launch(block: suspend AppleCameraEngine.() -> Unit): Job = scope.launch { engine.block() }

    fun start() {
        launch { start(backCamera()) }
        awaitTrue("the session to start") { session.isRunning }
        awaitTrue("the engine to report Open") { engine.state.value == EngineState.Open }
    }

    /** One `capture()` call running on main; [result] is set when it returns. */
    inner class Capture {
        var result: CameraKResult<CapturedImage>? = null
        val job: Job = scope.launch { result = engine.capture() }

        fun awaitResult(): CameraKResult<CapturedImage> {
            awaitTrue("capture to return") { result != null }
            return result!!.also { (it as? CameraKResult.Success)?.let { s -> savedFiles += s.value.path } }
        }
    }

    /** Starts a capture and waits until its request reaches the photo output. */
    fun capture(): Pair<Capture, FakePhotoCapturer.Request> {
        val before = capturer.requests.size
        val capture = Capture()
        awaitTrue("the capture request to reach the photo output") {
            capturer.requests.size > before || capture.result != null
        }
        capture.result?.let { fail("capture returned $it without asking the photo output for a photo") }
        return capture to capturer.requests[before]
    }

    @OptIn(ExperimentalForeignApi::class)
    fun tearDown() {
        scope.cancel()
        engine.close()
        drainSessionThenMain(custom)
        savedFiles.forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
    }
}

/** The bytes of the file at [path]. */
@OptIn(ExperimentalForeignApi::class)
internal fun fileBytes(path: String): ByteArray =
    (NSData.dataWithContentsOfFile(path) ?: error("no file was written at the result's path")).toByteArray()
