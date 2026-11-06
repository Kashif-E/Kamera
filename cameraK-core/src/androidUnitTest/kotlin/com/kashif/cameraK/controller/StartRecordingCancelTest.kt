@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import androidx.camera.video.VideoCapture
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * startRecording binds VideoCapture before it records, and VideoCapture in the session crops every
 * still (#136). A start that is cancelled while it waits for that bind never records, so the photo
 * session must come back without VideoCapture.
 */
@RunWith(RobolectricTestRunner::class)
class StartRecordingCancelTest {
    private val platform = FakeAndroidPlatform()

    // The engine's background work, on the test scheduler until [held]: then queued, so the
    // VideoCapture build a start waits for does not run.
    private class HoldableDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var held = false
        private val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) queued += context to block else delegate.dispatch(context, block)
        }

        fun release() {
            held = false
            queued.toList().also { queued.clear() }.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    @Test
    fun startCancelledWhileWaitingForTheVideoBind_leavesTheSessionWithoutVideoCapture() = runTest {
        val background = HoldableDispatcher(StandardTestDispatcher(testScheduler))
        val engine = platform.engine(testScheduler, background = background)
        open(platform, engine)
        background.held = true

        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.startRecording(VideoConfiguration { enableAudio = false })
        }
        platform.settle(testScheduler)
        // The VideoCapture build is queued on the held dispatcher; the caller gives up before it runs.
        assertTrue(start.isActive, "setup: startRecording should be waiting for the bind")
        start.cancel()
        // The build the start was waiting for now runs, after its caller left.
        background.release()
        platform.settle(testScheduler)

        assertEquals(
            emptyList(),
            platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "a cancelled start left VideoCapture bound",
        )

        // Every later bind (lens switch, rotation) must also come back without VideoCapture.
        val switch = async { engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(switch.await(), "the lens switch")

        assertEquals(
            emptyList(),
            platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "a cancelled start keeps adding VideoCapture to later binds",
        )
        engine.close()
    }
}
