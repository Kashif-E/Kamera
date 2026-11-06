@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plugin lifecycle contract: attach once, at most once per plugin, at once after the first
 * Ready, detach once, and close cancels what the plugin launched in its context's scope. Over
 * FakeCameraEngine, so no test opens a camera.
 */
class PluginLifecycleTest {
    /** Records lifecycle callbacks; optionally launches a long-lived coroutine in its context's scope. */
    private class FakePlugin(private val launchInScope: Boolean = false) : CameraKPlugin {
        var attachCount = 0
        var detachCount = 0
        var launchedJob: Job? = null

        override fun onAttach(context: CameraKPluginContext) {
            attachCount++
            if (launchInScope) {
                launchedJob = context.scope.launch { awaitCancellation() }
            }
        }

        override fun onDetach() {
            detachCount++
        }
    }

    private fun TestScope.fakeHolder(): CameraKStateHolder =
        CameraKStateHolder(CameraConfiguration(), { _, _ -> FakeCameraEngine() }, emptyList(), cameraKTestEnvironment())

    private fun TestScope.startReady(holder: CameraKStateHolder) {
        holder.start()
        runCurrent()
        assertTrue(holder.cameraState.value is CameraKState.Ready, "the holder did not reach Ready")
    }

    @Test
    fun start_attachesEachPluginOnce() = runTest {
        val sut = fakeHolder()
        val plugin = FakePlugin()

        sut.attachPlugin(plugin)
        startReady(sut)

        assertEquals(1, plugin.attachCount, "onAttach should fire exactly once on the first Ready")

        sut.close()
    }

    @Test
    fun attachPlugin_ignoresDuplicate() = runTest {
        val sut = fakeHolder()
        val plugin = FakePlugin()

        sut.attachPlugin(plugin)
        sut.attachPlugin(plugin) // duplicate — must be ignored
        startReady(sut)

        assertEquals(1, plugin.attachCount, "a duplicate attach must not double-attach the plugin")

        sut.close()
    }

    @Test
    fun attachPlugin_afterReady_attachesImmediately() = runTest {
        val sut = fakeHolder()
        startReady(sut)

        val plugin = FakePlugin()
        sut.attachPlugin(plugin)

        assertEquals(1, plugin.attachCount, "attaching after the first Ready should attach immediately")

        sut.close()
    }

    @Test
    fun detachPlugin_callsOnDetachOnceAndRemoves() = runTest {
        val sut = fakeHolder()
        val plugin = FakePlugin()
        sut.attachPlugin(plugin)
        startReady(sut)

        sut.detachPlugin(plugin)
        sut.detachPlugin(plugin) // already removed — must be a no-op

        assertEquals(1, plugin.detachCount, "onDetach should fire exactly once")

        sut.close()
    }

    @Test
    fun close_detachesPlugins_andCancelsTheirScopes() = runTest {
        val sut = fakeHolder()
        val plugin = FakePlugin(launchInScope = true)
        sut.attachPlugin(plugin)
        startReady(sut)

        assertNotNull(plugin.launchedJob, "plugin should have launched a coroutine in its context's scope")
        assertTrue(plugin.launchedJob!!.isActive, "the launched coroutine should be running")

        sut.closeAndJoin()

        assertEquals(1, plugin.detachCount, "close should detach the plugin")
        assertTrue(plugin.launchedJob!!.isCancelled, "close should cancel coroutines launched in the plugin's scope")
    }
}
