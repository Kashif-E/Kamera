package com.kashif.qrscannerplugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A code that stays in view while the app's events collector is slow costs a bounded amount of
 * work: the plugin does not leave one suspended coroutine per detection waiting on the event bus.
 */
class QRScannerEventBusTest {
    @Test
    fun aSlowEventsCollector_doesNotPileUpOneCoroutinePerDetection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val onCode = CompletableDeferred<(String) -> Unit>()
            val holder =
                CameraKStateHolder(
                    cameraConfiguration = CameraConfiguration(),
                    controllerFactory = {
                        CameraController(ImageFormat.JPEG, Directory.PICTURES, customGrabber = AbsentCamera())
                    },
                    coroutineScope = scope,
                )
            val plugin =
                QRScannerPlugin(scope) { _, deliver ->
                    onCode.complete(deliver)
                    ScannerHandle {}
                }
            val release = CompletableDeferred<Unit>()
            val scanned = mutableListOf<String>()
            val sawLast = CompletableDeferred<Unit>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                holder.events.filterIsInstance<CameraKEvent.QRCodeScanned>().collect { event ->
                    release.await()
                    scanned += event.qrCode
                    if (event.qrCode == "CODE-$LAST") sawLast.complete(Unit)
                }
            }
            holder.attachPlugin(plugin)
            holder.initialize()
            val deliver = withTimeout(5_000) { onCode.await() }

            repeat(DETECTIONS) { deliver("CODE-$it") }

            val pending = checkNotNull(holder.pluginScope.coroutineContext[Job]).activeDescendants()
            assertTrue(pending <= 2, "$pending plugin coroutines alive after $DETECTIONS detections")

            release.complete(Unit)
            withTimeout(5_000) { sawLast.await() }
            assertEquals("CODE-$LAST", scanned.last(), "the newest code reaches the bus")
            holder.shutdown()
        } finally {
            scope.cancel()
        }
    }

    private fun Job.activeDescendants(): Int =
        children.sumOf { child -> (if (child.isActive) 1 else 0) + child.activeDescendants() }

    private companion object {
        const val DETECTIONS = 500
        const val LAST = DETECTIONS - 1
    }
}
