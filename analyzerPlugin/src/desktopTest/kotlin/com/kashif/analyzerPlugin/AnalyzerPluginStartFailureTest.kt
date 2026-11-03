package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * When the frame analyzer fails to start on a camera that became ready, the failure reaches the app as
 * [CameraKEvent.PluginFailed] instead of only the log.
 */
class AnalyzerPluginStartFailureTest {
    @Test
    fun anAnalyzerThatFailsToStart_isReportedAsPluginFailed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val failure = IllegalStateException("analyzer failed to start")
            val holder =
                CameraKStateHolder(
                    cameraConfiguration = CameraConfiguration(),
                    controllerFactory = {
                        CameraController(ImageFormat.JPEG, Directory.PICTURES, customGrabber = AbsentCamera())
                    },
                    coroutineScope = scope,
                )
            val reported = CompletableDeferred<CameraKEvent.PluginFailed>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                reported.complete(holder.events.filterIsInstance<CameraKEvent.PluginFailed>().first())
            }
            holder.attachPlugin(AnalyzerPlugin(scope) { _, _ -> throw failure })

            holder.initialize()

            val event = withTimeout(5_000) { reported.await() }
            assertSame(failure, assertIs<CameraKError.Internal>(event.error).cause)
            assertEquals("AnalyzerPlugin", event.pluginId)
            holder.shutdown()
        } finally {
            scope.cancel()
        }
    }
}
