@file:Suppress("DEPRECATION")

package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.FakeFrameGrabber
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The 1.x holder surface kept, deprecated, on the 2.0 machinery until the slice that replaces each
 * member: the 1.x constructor over a factory-backed engine, initialize, shutdown, getController,
 * the plugins attached before the first session and the 1.x commands on the facade.
 */
class LegacyHolderSurfaceTest {
    private val home: File = Files.createTempDirectory("camerak-holder-home").toFile()
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        hostScope.cancel()
        home.deleteRecursively()
    }

    private fun controller(): CameraController = CameraController(
        imageFormat = ImageFormat.JPEG,
        directory = Directory.PICTURES,
        customGrabber = FakeFrameGrabber().apply { openGate() },
        sessionDispatcher = Dispatchers.IO,
        recorderFactory = { _, _ -> error("The holder surface tests do not record") },
        userHome = home,
    )

    private fun holder(scope: CoroutineScope = hostScope, factory: suspend () -> CameraController) = CameraKStateHolder(
        cameraConfiguration = CameraConfiguration(),
        controllerFactory = factory,
        coroutineScope = scope,
    )

    private suspend fun CameraKStateHolder.awaitState(predicate: (CameraKState) -> Boolean): CameraKState =
        withTimeout(TIMEOUT_MS) { cameraState.first(predicate) }

    private class FakePlugin(private val onAttached: (CameraKStateHolder) -> Unit = {}) : CameraKPlugin {
        val attaches = AtomicInteger()
        val detaches = AtomicInteger()

        override fun onAttach(stateHolder: CameraKStateHolder) {
            attaches.incrementAndGet()
            onAttached(stateHolder)
        }

        override fun onDetach() {
            detaches.incrementAndGet()
        }
    }

    @Test
    fun legacyConstructorRunsTheFactoryOnFirstStartOnTheHostDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, HOST_THREAD) }
        val scope = CoroutineScope(Job() + executor.asCoroutineDispatcher())
        val factoryThreads = CopyOnWriteArrayList<String>()
        try {
            val sut = holder(scope) {
                factoryThreads += Thread.currentThread().name
                controller()
            }
            assertEquals(emptyList(), factoryThreads.toList(), "the factory ran at construction")

            sut.start()
            val state = sut.awaitState { it is CameraKState.Ready || it is CameraKState.Error }

            assertIs<CameraKState.Ready>(state, "state is $state")
            assertEquals(listOf(HOST_THREAD), factoryThreads.toList(), "the threads the factory ran on")
            sut.closeAndJoin()
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun throwingFactoryGivesErrorInternalAndInitializeRethrows() = runBlocking {
        val failure = IllegalStateException("no camera here")
        val sut = holder { throw failure }

        val thrown = assertFailsWith<IllegalStateException> { sut.initialize() }

        assertSame(failure, thrown, "initialize rethrew")
        val state = sut.cameraState.value
        val error = assertIs<CameraKError.Internal>(assertIs<CameraKState.Error>(state, "state is $state").error)
        assertSame(failure, error.cause)
        sut.shutdown()
    }

    @Test
    fun initializeReturnsAtReadyWithTheFactoryControllerAsReadyController() = runBlocking {
        val controller = controller()
        val sut = holder { controller }

        sut.initialize()

        val state = sut.cameraState.value
        assertSame(controller, assertIs<CameraKState.Ready>(state, "state is $state").controller)
        sut.shutdown()
    }

    @Test
    fun pluginsAttachedBeforeInitializeAttachOnceAtReady() = runBlocking {
        val facadeAtAttach = AtomicReference<CameraController?>()
        val plugin = FakePlugin { facadeAtAttach.set(it.getController()) }
        val controller = controller()
        val sut = holder { controller }
        sut.attachPlugin(plugin)
        assertEquals(0, plugin.attaches.get(), "attached before the camera was ready")

        sut.initialize()

        assertEquals(1, plugin.attaches.get(), "attaches at the first Ready")
        assertSame(controller, facadeAtAttach.get(), "the facade a plugin sees in onAttach")

        sut.stop()
        sut.awaitState { it == CameraKState.Idle }
        sut.start()
        sut.awaitState { it is CameraKState.Ready }

        assertEquals(1, plugin.attaches.get(), "a second session attached the plugin again")
        sut.shutdown()
    }

    @Test
    fun shutdownDetachesPluginsAndCancelsPluginScopeBeforeReturningThenCloses() = runBlocking {
        var launched: Job? = null
        val plugin = FakePlugin { launched = it.pluginScope.launch { awaitCancellation() } }
        val sut = holder { controller() }
        sut.attachPlugin(plugin)
        sut.initialize()
        assertTrue(assertNotNull(launched, "the plugin launched nothing").isActive)

        sut.shutdown()

        assertEquals(1, plugin.detaches.get(), "detached by the time shutdown returned")
        assertTrue(launched!!.isCancelled, "the plugin's coroutine outlived shutdown")
        assertEquals(CameraKState.Closed, sut.cameraState.value)
        sut.closeAndJoin()
    }

    @Test
    fun getControllerIsTheFacadeFromFirstReadyUntilClose() = runBlocking {
        val controller = controller()
        val sut = holder { controller }
        assertNull(sut.getController(), "a facade before the first Ready")

        sut.initialize()
        assertSame(controller, sut.getController())

        sut.stop()
        sut.awaitState { it == CameraKState.Idle }
        assertSame(controller, sut.getController(), "the facade after a stop")

        sut.shutdown()
        assertNull(sut.getController(), "a facade after close")
    }

    @Test
    fun legacyCaptureImageRunsOnTheFacade() = runBlocking {
        val sut = holder { controller() }
        sut.initialize()
        val capturing = CopyOnWriteArrayList<Boolean>()
        val watcher = hostScope.launch(Dispatchers.Unconfined) { sut.uiState.collect { capturing += it.isCapturing } }
        val event = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            sut.events.first { it is CameraKEvent.ImageCaptured || it is CameraKEvent.CaptureFailed }
        }

        sut.captureImage()

        val captured = assertIs<CameraKEvent.ImageCaptured>(withTimeout(TIMEOUT_MS) { event.await() })
        val file = File(assertIs<ImageCaptureResult.SuccessWithFile>(captured.result).filePath)
        assertTrue(file.canonicalPath.startsWith(home.canonicalPath), "the capture was not written by the facade")
        withTimeout(TIMEOUT_MS) { sut.uiState.first { !it.isCapturing } }
        assertTrue(true in capturing, "isCapturing never went true: $capturing")
        assertEquals(false, sut.uiState.value.isCapturing)
        watcher.cancel()
        sut.shutdown()
    }

    @Test
    fun legacyCaptureImageWithoutAFacadeEmitsCaptureFailed() = runBlocking {
        val sut = holder { controller() }
        val event = hostScope.async(start = CoroutineStart.UNDISPATCHED) { sut.events.first() }

        sut.captureImage()

        val failed = assertIs<CameraKEvent.CaptureFailed>(withTimeout(TIMEOUT_MS) { event.await() })
        assertEquals("Camera not initialized", failed.exception.message)
        sut.shutdown()
    }

    @Test
    fun legacyStartRecordingWithoutAFacadeEmitsCameraNotInitialized() = runBlocking {
        val sut = holder { controller() }
        val event = hostScope.async(start = CoroutineStart.UNDISPATCHED) { sut.events.first() }

        sut.startRecording(VideoConfiguration())

        val failed = assertIs<CameraKEvent.RecordingFailed>(withTimeout(TIMEOUT_MS) { event.await() })
        assertEquals("Camera not initialized", failed.exception.message)
        sut.shutdown()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val HOST_THREAD = "camerak-host"
    }
}
