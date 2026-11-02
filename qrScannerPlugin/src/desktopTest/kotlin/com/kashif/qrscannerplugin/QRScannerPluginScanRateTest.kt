package com.kashif.qrscannerplugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The scan interval given to [QRScannerPlugin] is the one the desktop scanner uses. */
@OptIn(ExperimentalCoroutinesApi::class)
class QRScannerPluginScanRateTest {
    @BeforeTest
    fun installMain() {
        // Desktop delivers codes on Dispatchers.Main, which this JVM test has no UI thread for.
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1))
    }

    @AfterTest
    fun removeMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun thePluginsScanInterval_reachesTheDesktopScanner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val grabsAtFirstDetection = AtomicInteger(-1)
            val sixtyMoreFrames = CompletableDeferred<Unit>()
            val camera =
                StillCamera(qrImage("HELLO", 320, 240)) { grabs ->
                    val first = grabsAtFirstDetection.get()
                    if (first >= 0 && grabs - first >= 60) sixtyMoreFrames.complete(Unit)
                }
            val holder =
                CameraKStateHolder(
                    cameraConfiguration = CameraConfiguration(),
                    controllerFactory = {
                        CameraController(ImageFormat.JPEG, Directory.PICTURES, customGrabber = camera)
                    },
                    coroutineScope = scope,
                )
            val plugin = QRScannerPlugin(scope, scanIntervalMs = 3_600_000)
            val detections = AtomicInteger()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                plugin.getQrCodeFlow().collect {
                    if (detections.incrementAndGet() == 1) grabsAtFirstDetection.set(camera.grabs)
                }
            }
            holder.attachPlugin(plugin)

            holder.initialize()
            withTimeout(10_000) { sixtyMoreFrames.await() }

            assertEquals(
                1,
                detections.get(),
                "detections while 60 more frames (at least 600 ms) of the same code arrived, with a one-hour scan interval",
            )
            holder.shutdown()
        } finally {
            scope.cancel()
        }
    }
}
