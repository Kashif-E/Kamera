package com.kashif.ocrPlugin

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
 * Text that stays in view while the app's events collector is slow costs a bounded amount of work:
 * the plugin does not leave one suspended coroutine per recognition waiting on the event bus.
 */
class OcrEventBusTest {
    @Test
    fun aSlowEventsCollector_doesNotPileUpOneCoroutinePerRecognition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val onText = CompletableDeferred<(String) -> Unit>()
            val holder =
                CameraKStateHolder(
                    cameraConfiguration = CameraConfiguration(),
                    controllerFactory = {
                        CameraController(ImageFormat.JPEG, Directory.PICTURES, customGrabber = AbsentCamera())
                    },
                    coroutineScope = scope,
                )
            val plugin =
                OcrPlugin(scope, OcrConfig()) { _, _, deliver ->
                    onText.complete(deliver)
                    RecognitionHandle {}
                }
            val release = CompletableDeferred<Unit>()
            val scanned = mutableListOf<String>()
            val sawLast = CompletableDeferred<Unit>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                holder.events.filterIsInstance<CameraKEvent.TextRecognized>().collect { event ->
                    release.await()
                    scanned += event.text
                    if (event.text == "TEXT-$LAST") sawLast.complete(Unit)
                }
            }
            holder.attachPlugin(plugin)
            holder.initialize()
            val deliver = withTimeout(5_000) { onText.await() }

            repeat(DETECTIONS) { deliver("TEXT-$it") }

            val pending = checkNotNull(holder.pluginScope.coroutineContext[Job]).activeDescendants()
            assertTrue(pending <= 2, "$pending plugin coroutines alive after $DETECTIONS recognitions")

            release.complete(Unit)
            withTimeout(5_000) { sawLast.await() }
            assertEquals("TEXT-$LAST", scanned.last(), "the newest text reaches the bus")
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
