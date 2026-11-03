@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.Foundation.NSFileManager
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The 1.x facade the engine carries to Ready.controller works over that engine: a scanner's metadata
 * delegate reaches the engine's session and a capture listener receives a capture the facade takes.
 * No holder: the facade is read through LegacyControllerAccess, as Ready.controller reads it.
 */
class LegacyFacadeLiveTest {
    @Test
    fun metadataDelegateAndCaptureListenerWorkThroughTheFacade() {
        val session = FakeSessionOps()
        val capturer = FakePhotoCapturer()
        val custom = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            sessionFactory = { session },
            cameraDevices = { _, _ -> FakeDevice(uniqueId = AppleEngineDriver.BACK_ID) },
            photoCapturer = capturer,
        )
        val engine = AppleCameraEngine(CameraConfiguration(), CameraKEnvironment(), custom) {
            listOf(AppleEngineDriver.lensInfo(AppleEngineDriver.BACK_ID, CameraLens.BACK))
        }
        val scope = MainScope()
        var saved: String? = null
        try {
            scope.launch { engine.start(backCamera()) }
            awaitTrue("the engine to open") { engine.state.value == EngineState.Open }

            val facade = (engine as LegacyControllerAccess).legacyController
            assertSame(facade, engine.legacyController, "the engine carries one facade")

            facade.setMetadataObjectsDelegate(object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {})
            awaitTrue("the scanner's metadata output to join the engine's session") {
                session.outputs.any { it is AVCaptureMetadataOutput }
            }

            val received = mutableListOf<ByteArray>()
            facade.addImageCaptureListener { received += it }
            var result: ImageCaptureResult? = null
            scope.launch { result = facade.takePictureToFile() }
            awaitTrue("the facade's capture to reach the engine's photo output") { capturer.requests.isNotEmpty() }
            val photo = ByteArray(64) { it.toByte() }
            capturer.requests.single().callbacks.photoProcessed(photo.toNSData(), null)
            capturer.requests.single().callbacks.captureFinished(null)

            awaitTrue("the capture listener to receive the photo") { received.isNotEmpty() }
            assertContentEquals(photo, received.single(), "the listener got bytes other than the photo's")
            awaitTrue("takePictureToFile to return") { result != null }
            saved = assertIs<ImageCaptureResult.SuccessWithFile>(result).filePath
        } finally {
            scope.cancel()
            engine.close()
            drainSessionThenMain(custom)
            saved?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }
}
