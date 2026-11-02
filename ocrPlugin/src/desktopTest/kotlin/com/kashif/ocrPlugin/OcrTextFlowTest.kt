package com.kashif.ocrPlugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recognised text reaches every collector of [OcrPlugin.textFlow], and nothing a consumer does to the
 * old channel stops it.
 */
class OcrTextFlowTest {
    @Test
    fun twoCollectors_bothReceiveEachRecognisedText() = withRecognizer { plugin, onText ->
        val first = plugin.scopeAsync { plugin.textFlow.first() }
        val second = plugin.scopeAsync { plugin.textFlow.first() }

        onText("INVOICE 42")

        assertEquals("INVOICE 42", withTimeout(2_000) { first.await() })
        assertEquals("INVOICE 42", withTimeout(2_000) { second.await() }, "the second collector got nothing")
    }

    @Suppress("DEPRECATION")
    @Test
    fun aConsumerClosingTheOldChannel_doesNotStopTextFlow() = withRecognizer { plugin, onText ->
        plugin.ocrFlow.close()
        val received = plugin.scopeAsync { plugin.textFlow.first() }

        onText("RECEIPT")

        assertEquals("RECEIPT", withTimeout(2_000) { received.await() })
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun <T> OcrPlugin.scopeAsync(block: suspend () -> T) =
        scope.async(start = CoroutineStart.UNDISPATCHED) { block() }

    private fun withRecognizer(test: suspend (OcrPlugin, (String) -> Unit) -> Unit) = runBlocking {
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
            holder.attachPlugin(plugin)
            holder.initialize()

            test(plugin, withTimeout(5_000) { onText.await() })
            holder.shutdown()
        } finally {
            scope.cancel()
        }
    }
}
