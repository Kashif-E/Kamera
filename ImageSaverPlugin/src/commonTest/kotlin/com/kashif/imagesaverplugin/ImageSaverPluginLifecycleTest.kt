package com.kashif.imagesaverplugin

import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageSaverPluginLifecycleTest {
    private class NoOpSaver(config: ImageSaverConfig) : ImageSaverPlugin(config) {
        override suspend fun saveImage(byteArray: ByteArray, imageName: String?): String? = null
        override fun getByteArrayFrom(path: String): ByteArray = ByteArray(0)
    }

    @Test
    fun autoSaveOff_attachLaunchesNothing() = runTest {
        withHolder { holder ->
            val plugin = NoOpSaver(ImageSaverConfig(isAutoSave = false))

            plugin.onAttach(holder)

            assertEquals(0, holder.activePluginJobs(), "manual-save plugin must not watch the camera")
        }
    }

    @Test
    fun autoSaveOn_detachCancelsTheReadyCollector() = runTest {
        withHolder { holder ->
            val plugin = NoOpSaver(ImageSaverConfig(isAutoSave = true))

            plugin.onAttach(holder)
            assertEquals(1, holder.activePluginJobs(), "auto-save should wait for Ready in pluginScope")

            plugin.onDetach()
            assertEquals(0, holder.activePluginJobs(), "onDetach must cancel the auto-save collector")
        }
    }
}

private suspend fun TestScope.withHolder(block: suspend (CameraKStateHolder) -> Unit) {
    val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
    try {
        block(
            CameraKStateHolder(
                cameraConfiguration = CameraConfiguration(),
                controllerFactory = { error("unit tests have no camera") },
                coroutineScope = hostScope,
            ),
        )
    } finally {
        hostScope.cancel()
    }
}

private fun CameraKStateHolder.activePluginJobs(): Int =
    checkNotNull(pluginScope.coroutineContext[Job]).children.count { it.isActive }
