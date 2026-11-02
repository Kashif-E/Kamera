package com.kashif.imagesaverplugin

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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * An auto-save that fails reaches the app as [CameraKEvent.PluginFailed] carrying the save's cause,
 * instead of a null path nobody sees.
 */
class ImageSaverAutoSaveFailureTest {
    private class FailingSaver(private val failure: Throwable) : ImageSaverPlugin(ImageSaverConfig(isAutoSave = true)) {
        override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? = null

        override suspend fun save(byteArray: ByteArray, imageName: String?): ImageSaveResult =
            ImageSaveResult.Failed(failure)

        override fun getByteArrayFrom(path: String): ByteArray = ByteArray(0)
    }

    @Test
    fun aFailedAutoSave_isReportedAsPluginFailed() = runTest {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
            val failure = IllegalStateException("storage full")
            val reported = CompletableDeferred<Throwable>()
            hostScope.launch(start = CoroutineStart.UNDISPATCHED) {
                reported.complete(holder.events.filterIsInstance<CameraKEvent.PluginFailed>().first().error)
            }
            val plugin = FailingSaver(failure)
            plugin.onAttach(holder)

            plugin.captureListener(byteArrayOf(1, 2, 3))

            // Real time: the save runs on Dispatchers.IO, outside the test scheduler.
            val error = withContext(Dispatchers.Default) { withTimeout(5_000) { reported.await() } }
            assertSame(failure, error)
        } finally {
            hostScope.cancel()
        }
    }
}
