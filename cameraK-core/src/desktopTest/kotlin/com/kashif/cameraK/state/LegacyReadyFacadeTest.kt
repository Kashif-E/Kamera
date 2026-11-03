@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.FakeFrameGrabber
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.DesktopLegacySession
import com.kashif.cameraK.engine.LegacyControllerEngine
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewFrame
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The holder half of the legacy facade channel: over the 1.x bridge's engine, `Ready.controller`
 * is the controller the bridge wrapped, and every Ready the holder publishes is the same value.
 */
class LegacyReadyFacadeTest {
    private object NoPreview : PlatformPreviewSource {
        override val frames: StateFlow<PreviewFrame?> = MutableStateFlow(null)
    }

    @Test
    fun readyControllerIsTheControllerTheBridgeWrapped() = runBlocking {
        val controller = CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = FakeFrameGrabber().apply { openGate() },
            sessionDispatcher = Dispatchers.IO,
            recorderFactory = { _, _ -> error("The facade test does not record") },
        )
        val parent = Job()
        val environment = CameraKEnvironment(
            dispatchers = CameraKDispatchers(
                main = Dispatchers.Default,
                default = Dispatchers.Default,
                io = Dispatchers.IO,
            ),
            parentJob = parent,
        )
        val engine = LegacyControllerEngine(
            legacyController = controller,
            preview = NoPreview,
            session = DesktopLegacySession(controller, Dispatchers.IO),
            environment = environment,
        )
        val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> engine }, emptyList(), environment)
        try {
            holder.start()
            val first = withTimeout(TIMEOUT_MS) {
                holder.cameraState.first {
                    it is CameraKState.Ready ||
                        it is CameraKState.Error
                }
            }
            @Suppress("DEPRECATION")
            assertSame(controller, assertIs<CameraKState.Ready>(first, "state is $first").controller)

            holder.stop()
            withTimeout(TIMEOUT_MS) { holder.cameraState.first { it == CameraKState.Idle } }
            holder.start()
            val second = withTimeout(TIMEOUT_MS) {
                holder.cameraState.first {
                    it is CameraKState.Ready ||
                        it is CameraKState.Error
                }
            }

            assertSame(first, second, "the Ready published after a stop and a start")
        } finally {
            holder.closeAndJoin()
            parent.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
