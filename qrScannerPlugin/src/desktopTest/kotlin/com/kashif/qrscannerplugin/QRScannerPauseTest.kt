package com.kashif.qrscannerplugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pausing the QR scanner stops the platform scanner, so no frame is decoded while paused, and the
 * pause holds until the app resumes: the camera becoming ready does not undo it.
 */
class QRScannerPauseTest {
    // Unconfined: the plugin handles Ready inside initialize(), so each check below sees its effect.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val starts = AtomicInteger()
    private val stops = AtomicInteger()
    private val plugin =
        QRScannerPlugin(scope) { _, _ ->
            starts.incrementAndGet()
            ScannerHandle { stops.incrementAndGet() }
        }
    private val holder =
        CameraKStateHolder(
            cameraConfiguration = CameraConfiguration(),
            controllerFactory = {
                CameraController(ImageFormat.JPEG, Directory.PICTURES, customGrabber = AbsentCamera())
            },
            coroutineScope = scope,
        )

    @AfterTest
    fun tearDown() {
        holder.shutdown()
        scope.cancel()
    }

    @Test
    fun pausing_stopsTheScanner() = runBlocking {
        holder.attachPlugin(plugin)
        holder.initialize()
        assertEquals(1, starts.get(), "scanners started when the camera became ready")

        plugin.pauseScanning()

        assertEquals(1, stops.get(), "scanners stopped by the pause; a running scanner decodes every frame")
    }

    @Test
    fun aPauseBeforeTheCameraIsReady_survivesReady() = runBlocking {
        holder.attachPlugin(plugin)
        plugin.pauseScanning()

        holder.initialize()

        assertEquals(0, starts.get(), "scanners started on Ready while the app had paused scanning")
    }

    @Test
    fun resumingAfterAPause_startsScanningAgain() = runBlocking {
        holder.attachPlugin(plugin)
        holder.initialize()
        plugin.pauseScanning()

        plugin.resumeScanning()

        assertEquals(2, starts.get(), "scanners started: once on Ready, once on resume")
        assertEquals(1, stops.get(), "scanners stopped: once, by the pause")
    }
}
