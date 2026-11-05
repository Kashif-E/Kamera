@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Until C1', plugins and apps reach the camera through `Ready.controller`. Over the desktop engine
 * that controller is a facade over the same engine, with no session of its own: an analyzer
 * collecting its frames and a capture listener both work on the engine's one grab loop (§12.1).
 * The engine is read through LegacyControllerAccess, with no holder.
 */
class LegacyFacadeLiveTest {
    private val home: File = Files.createTempDirectory("camerak-facade").toFile()
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun analyzerAndCaptureListenerWorkThroughTheFacade() = runBlocking {
        val camera = FakeFrameGrabber().apply { openGate() }
        // A 4:3 configuration for the 4:3 camera, so the blit crops nothing (D4').
        val config = CameraConfiguration(aspectRatio = AspectRatio.RATIO_4_3)
        val engine = DesktopCameraEngine(
            config,
            CameraKEnvironment(CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default)),
            CustomGrabberSource(camera),
            media = DesktopMedia(userHome = home),
            facade = { CameraController(it, config) },
        )
        val facade = (engine as LegacyControllerAccess).legacyController
        try {
            assertSame(engine, facade.engine, "the facade is over another engine")
            assertEquals(CameraKResult.Success(Unit), withTimeout(TIMEOUT_MS) { engine.start(desired) })

            // An analyzer, as AnalyzerPlugin.desktop collects it.
            val analyzed = withTimeout(TIMEOUT_MS) { facade.frameFlow.first() }
            assertEquals(64 to 48, analyzed.width to analyzed.height)

            var heard: ByteArray? = null
            facade.addImageCaptureListener { heard = it }
            val result = withTimeout(TIMEOUT_MS) { facade.takePictureToFile() }

            val saved = File(assertIs<ImageCaptureResult.SuccessWithFile>(result, "capture: $result").filePath)
            assertContentEquals(saved.readBytes(), assertNotNull(heard, "the capture listener heard nothing"))
            assertEquals(1, camera.startCount.get(), "the facade opened the camera a second time")
            assertEquals(1, camera.maxConcurrentGrabs.get(), "grab() calls that overlapped")
            assertEquals(1, camera.grabThreads.size, "threads that called grab(): ${camera.grabThreads}")
        } finally {
            withTimeout(TIMEOUT_MS) { engine.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
