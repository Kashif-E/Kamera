@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.coroutines.suspendRunCatching
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** Migration only, until C1': the channel that carries the 1.x facade to `Ready.controller`. */
internal interface LegacyControllerAccess {
    /**
     * One facade per engine. Read only through `Ready.controller`, so once the engine has published
     * Open: the 1.x holder constructor's engine creates its controller in its first start and throws
     * [IllegalStateException] before it.
     */
    val legacyController: CameraController
}

/** A 1.x controller's session calls. Each runs on the thread its platform's 1.x code expects. */
internal interface LegacySession {
    suspend fun start()

    suspend fun stop()

    suspend fun close()

    /** Runs [block], a read of the 1.x controller, on that same thread. */
    suspend fun <T> onSessionThread(block: () -> T): T
}

/**
 * The engine every platform bridge returns until its real engine lands: a 1.x controller behind
 * the engine interface, reporting what the 1.x holder reported. It publishes Open once the
 * session's start call returns, because the 1.x controllers report no state of their own, and it
 * never reports Interrupted or a runtime Failed: their runtime errors stay log-only, as in 1.x.
 * Commands are refused with `InvalidState`, logged at warning the first time each kind is refused
 * on this engine; until the platform engines land, plugins and the
 * Sample issue them on `Ready.controller`. Exempt from the contract kit.
 */
internal class LegacyControllerEngine private constructor(
    controller: CameraController?,
    session: LegacySession?,
    private val controllerFactory: (suspend () -> CameraController)?,
    private val factoryDispatcher: CoroutineDispatcher?,
    private val previewSource: PlatformPreviewSource?,
    private val environment: CameraKEnvironment,
) : CameraEngine,
    LegacyControllerAccess {
    /** A bridge's engine, over a controller the bridge built; [session] makes its calls on the threads they need. */
    constructor(
        legacyController: CameraController,
        preview: PlatformPreviewSource,
        session: LegacySession,
        environment: CameraKEnvironment,
    ) : this(legacyController, session, null, null, preview, environment)

    /**
     * The 1.x holder constructor's engine: it runs [controllerFactory] in its first start and drives
     * that controller through a [DirectLegacySession], both on [dispatcher], where the 1.x holder's
     * initialize and shutdown ran. A factory that throws fails that start with `Internal`; the next
     * start runs it again. Its [preview] is never read: the 1.x preview takes `Ready.controller`.
     */
    constructor(
        controllerFactory: suspend () -> CameraController,
        dispatcher: CoroutineDispatcher,
        environment: CameraKEnvironment,
    ) : this(null, null, controllerFactory, dispatcher, null, environment)

    // Written once, by the first start that creates the controller; read from any thread.
    @Volatile
    private var controller: CameraController? = controller

    @Volatile
    private var session: LegacySession? = session

    override val legacyController: CameraController
        get() = controller ?: throw IllegalStateException("The 1.x controller is created by the engine's first start")

    override val preview: PlatformPreviewSource
        get() = previewSource ?: throw IllegalStateException("The 1.x holder's engine has no preview source")
    private val _state = MutableStateFlow<EngineState>(EngineState.Closed)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    override val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    override val recordingEvents: Flow<EngineRecordingEvent> = emptyFlow()
    override val frames: FrameSource = InactiveFrameSource()

    // The engine's own scope, under the environment's job. Only the release runs in it.
    private val scope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "Releasing the 1.x camera session failed", e) },
    )

    // Start, stop and the session close run one at a time, so no session call overlaps another.
    private val sessionLock = Mutex()
    private val closed = atomic(false)
    private val closeDone = Job()
    private var listeningForOrientation = false

    // The command kinds this engine has refused so far; each is logged the first time only.
    private val refusedOperations = atomic(emptySet<CameraOperation>())

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (closed.value) return CameraKResult.Failure(CameraKError.Closed)
        return sessionLock.withLock {
            when {
                closed.value -> CameraKResult.Failure(CameraKError.Closed)
                _state.value == EngineState.Open -> CameraKResult.Success(Unit)
                else -> open()
            }
        }
    }

    private suspend fun open(): CameraKResult<Unit> {
        publish(EngineState.Opening)
        return try {
            createController() ?: startAndReadBack()
        } catch (e: CancellationException) {
            // The session may be running when the cancellation lands; a cancelled start leaves the camera closed.
            withContext(NonCancellable) { stopSession() }
            publish(EngineState.Closed)
            throw e
        }
    }

    // The factory-backed engine's first start creates its controller; null once there is one.
    private suspend fun createController(): CameraKResult<Unit>? {
        if (session != null) return null
        val factory = checkNotNull(controllerFactory) { "An engine without a session has a controller factory" }
        val dispatcher = checkNotNull(factoryDispatcher) { "An engine without a session has a factory dispatcher" }
        // Caught on the factory's own dispatcher, so the throwable reaches the error as it was thrown.
        val created = withContext(dispatcher) { suspendRunCatching { factory() } }
        created.exceptionOrNull()?.let { e ->
            CameraKLogger.e(TAG, "Creating the 1.x camera controller failed", e)
            val error = CameraKError.Internal(e)
            publish(EngineState.Failed(error))
            return CameraKResult.Failure(error)
        }
        val newController = created.getOrThrow()
        controller = newController
        session = DirectLegacySession(newController, dispatcher)
        return null
    }

    private suspend fun startAndReadBack(): CameraKResult<Unit> {
        val session = checkNotNull(session) { "The engine starts a session only once it has one" }
        suspendRunCatching { session.start() }.exceptionOrNull()?.let { e ->
            CameraKLogger.e(TAG, "Starting the 1.x camera session failed", e)
            val error = CameraKError.Internal(e)
            publish(EngineState.Failed(error))
            return CameraKResult.Failure(error)
        }
        readBack(session, legacyController)
        if (closed.value) return CameraKResult.Failure(CameraKError.Closed)
        publish(EngineState.Open)
        return CameraKResult.Success(Unit)
    }

    // The 1.x getters, read once per start, the way the 1.x holder read them for its UI state.
    private suspend fun readBack(session: LegacySession, legacyController: CameraController) {
        val read = suspendRunCatching {
            session.onSessionThread {
                val capabilities = legacyController.getCameraCapabilities()
                val camera = legacyController.getCameraLens()?.let { capabilities.lenses(it).firstOrNull() }
                    ?: capabilities.allLenses.firstOrNull()
                val values = ReadBack(
                    capabilities = capabilities,
                    hardware = HardwareState(
                        flashMode = legacyController.getFlashMode() ?: FlashMode.OFF,
                        torchMode = legacyController.getTorchMode() ?: TorchMode.OFF,
                        camera = camera,
                        targetOrientation = null,
                    ),
                    zoom = ZoomState(legacyController.getZoom(), 1f, legacyController.getMaxZoom()),
                    orientation = legacyController.getDeviceOrientation(),
                )
                if (!listeningForOrientation) {
                    legacyController.setOnOrientationChangedListener {
                        if (!closed.value) _deviceOrientation.value = it
                    }
                    listeningForOrientation = true
                }
                values
            }
        }
        read.onFailure {
            CameraKLogger.e(TAG, "Reading the 1.x controller's hardware failed; keeping the last values", it)
        }
        read.getOrNull()?.let {
            _capabilities.value = it.capabilities
            _hardware.value = it.hardware
            _zoom.value = it.zoom
            _deviceOrientation.value = it.orientation
        }
    }

    override suspend fun stop() {
        if (closed.value) return
        sessionLock.withLock {
            if (closed.value || _state.value == EngineState.Closed) return
            try {
                stopSession()
            } finally {
                publish(EngineState.Closed)
            }
        }
    }

    private suspend fun stopSession() {
        val session = session ?: return
        suspendRunCatching { session.stop() }
            .onFailure { CameraKLogger.e(TAG, "Stopping the 1.x camera session failed", it) }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        _state.value = EngineState.Closed
        // ATOMIC and NonCancellable: the session closes even when the environment's job is already cancelled.
        scope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                try {
                    sessionLock.withLock { release() }
                } finally {
                    closeDone.complete()
                }
            }
        }
        scope.cancel()
    }

    private suspend fun release() {
        val session = session ?: return
        if (listeningForOrientation) {
            suspendRunCatching { session.onSessionThread { legacyController.setOnOrientationChangedListener(null) } }
                .onFailure { CameraKLogger.e(TAG, "Removing the 1.x orientation listener failed", it) }
            listeningForOrientation = false
        }
        suspendRunCatching { session.close() }
            .onFailure { CameraKLogger.e(TAG, "Closing the 1.x camera session failed", it) }
    }

    override suspend fun closeAndJoin() {
        close()
        closeDone.join()
    }

    // A state write after close leaves Closed, whichever of the two lands first.
    private fun publish(next: EngineState) {
        _state.update { if (closed.value) EngineState.Closed else next }
    }

    override suspend fun capture(): CameraKResult<CapturedImage> = refuse(CameraOperation.CAPTURE)

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = refuse(CameraOperation.ZOOM)

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = refuse(CameraOperation.FLASH)

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = refuse(CameraOperation.TORCH)

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> =
        refuse(CameraOperation.LENS)

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> =
        refuse(CameraOperation.ORIENTATION)

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> =
        refuse(CameraOperation.RECORD_PREPARE)

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        refuse(CameraOperation.RECORD_START)

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = refuse(CameraOperation.RECORD_STOP)

    override suspend fun pauseRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_PAUSE)

    override suspend fun resumeRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_RESUME)

    private fun refuse(operation: CameraOperation): CameraKResult<Nothing> {
        if (closed.value) return CameraKResult.Failure(CameraKError.Closed)
        if (operation !in refusedOperations.getAndUpdate { it + operation }) {
            CameraKLogger.w(TAG, "The 1.x bridge refuses the $operation command; issue it on Ready.controller")
        }
        return CameraKResult.Failure(CameraKError.InvalidState(operation))
    }

    private class ReadBack(
        val capabilities: CameraCapabilities,
        val hardware: HardwareState,
        val zoom: ZoomState,
        val orientation: DeviceOrientation,
    )

    private companion object {
        const val TAG = "CameraK"
    }
}

/**
 * The 1.x holder constructor's session: the controller's own calls, each on [dispatcher], the host
 * scope's dispatcher, as the 1.x holder made them. Close stops the session, then cleans up, as 1.x
 * `shutdown()` did. A call that throws rethrows the controller's own throwable, as the 1.x holder's
 * initialize did.
 */
internal class DirectLegacySession(
    private val controller: CameraController,
    private val dispatcher: CoroutineDispatcher,
) : LegacySession {
    override suspend fun start() = onDispatcher { controller.startSession() }

    override suspend fun stop() = onDispatcher { controller.stopSession() }

    override suspend fun close() = onDispatcher {
        controller.stopSession()
        controller.cleanup()
    }

    override suspend fun <T> onSessionThread(block: () -> T): T = onDispatcher(block)

    // Caught on the dispatcher and rethrown here: a throwable that crosses withContext may arrive as a copy.
    private suspend fun <T> onDispatcher(block: () -> T): T =
        withContext(dispatcher) { suspendRunCatching { block() } }.getOrThrow()
}
