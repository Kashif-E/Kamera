package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the plugin lifecycle contract on the real (desktop) CameraController.
 *
 * The desktop controller's startSession() is fire-and-forget (it tries to open a webcam on a
 * background scope and the failure is caught), so initialize()/shutdown() run fine without a camera
 * and we can exercise attach/detach/shutdown/scope-cancellation deterministically.
 */
class PluginLifecycleTest {

    private fun newController(): CameraController = CameraController(ImageFormat.JPEG, Directory.PICTURES)

    private fun holder(scope: CoroutineScope, factory: suspend () -> CameraController = { newController() }) =
        CameraKStateHolder(
            cameraConfiguration = CameraConfiguration(),
            controllerFactory = factory,
            coroutineScope = scope,
        )

    /** Records lifecycle callbacks; optionally launches a long-lived coroutine in pluginScope. */
    private class FakePlugin(private val launchInScope: Boolean = false) : CameraKPlugin {
        var attachCount = 0
        var detachCount = 0
        var launchedJob: Job? = null

        override fun onAttach(stateHolder: CameraKStateHolder) {
            attachCount++
            if (launchInScope) {
                launchedJob = stateHolder.pluginScope.launch { awaitCancellation() }
            }
        }

        override fun onDetach() {
            detachCount++
        }
    }

    @Test
    fun initialize_attachesEachPluginOnce() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope)
        val plugin = FakePlugin()

        sut.attachPlugin(plugin)
        sut.initialize()

        assertEquals(1, plugin.attachCount, "onAttach should fire exactly once on initialize")

        sut.shutdown()
        scope.cancel()
    }

    @Test
    fun attachPlugin_ignoresDuplicate() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope)
        val plugin = FakePlugin()

        sut.attachPlugin(plugin)
        sut.attachPlugin(plugin) // duplicate — must be ignored
        sut.initialize()

        assertEquals(1, plugin.attachCount, "a duplicate attach must not double-attach the plugin")

        sut.shutdown()
        scope.cancel()
    }

    @Test
    fun attachPlugin_afterInitialize_attachesImmediately() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope)
        sut.initialize()

        val plugin = FakePlugin()
        sut.attachPlugin(plugin)

        assertEquals(1, plugin.attachCount, "attaching after initialize should attach immediately")

        sut.shutdown()
        scope.cancel()
    }

    @Test
    fun detachPlugin_callsOnDetachOnceAndRemoves() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope)
        val plugin = FakePlugin()
        sut.attachPlugin(plugin)
        sut.initialize()

        sut.detachPlugin(plugin)
        sut.detachPlugin(plugin) // already removed — must be a no-op

        assertEquals(1, plugin.detachCount, "onDetach should fire exactly once")

        sut.shutdown()
        scope.cancel()
    }

    @Test
    fun shutdown_detachesPlugins_andCancelsPluginScope() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope)
        val plugin = FakePlugin(launchInScope = true)
        sut.attachPlugin(plugin)
        sut.initialize()

        assertNotNull(plugin.launchedJob, "plugin should have launched a coroutine in pluginScope")
        assertTrue(plugin.launchedJob!!.isActive, "the launched coroutine should be running")

        sut.shutdown()

        assertEquals(1, plugin.detachCount, "shutdown should detach the plugin")
        assertTrue(plugin.launchedJob!!.isCancelled, "shutdown should cancel coroutines launched in pluginScope")

        scope.cancel()
    }

    @Test
    fun getReadyCameraController_returnsControllerWhenReady() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope)
        sut.initialize()

        val controller = withTimeoutOrNull(2000) { sut.getReadyCameraController() }
        assertNotNull(controller, "should return the controller once Ready")

        sut.shutdown()
        scope.cancel()
    }

    @Test
    fun getReadyCameraController_returnsNullOnError_doesNotHang() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sut = holder(scope) { throw RuntimeException("boom") }

        try {
            sut.initialize()
        } catch (_: Exception) {
            // expected — initialize rethrows after setting Error state
        }

        // Wrap a genuine null return in a sentinel so we can tell it apart from a timeout (hang).
        val result = withTimeoutOrNull(2000) { sut.getReadyCameraController() ?: NULL_RETURNED }
        assertEquals(NULL_RETURNED, result, "should return null on Error, not hang forever")

        scope.cancel()
    }

    private companion object {
        const val NULL_RETURNED = "NULL_RETURNED"
    }
}
