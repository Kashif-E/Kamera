package com.kashif.cameraK.plugin

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.coroutines.isFatal
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.state.CameraUIState
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

/**
 * The configuration the holder gives its engine factory (Decision 14): a plugin present at creation
 * that needs frames sets [CameraConfiguration.frameAnalysis], so the first registration needs no
 * rebind. Otherwise [config] itself.
 */
internal fun effectiveConfig(config: CameraConfiguration, plugins: List<CameraKPlugin>): CameraConfiguration =
    if (plugins.any { it.needsFrames }) config.copy { frameAnalysis = true } else config

/**
 * A holder's plugins. Plugins added before the first session attach once, when that session first
 * reaches Ready; a plugin added after it attaches at once. One lock guards the list, the contexts
 * and the attached flag, and `onAttach` and `onDetach` run outside it, so a plugin that calls back
 * into the holder cannot stall or deadlock another thread.
 *
 * Each attachment gets its own [CameraKPluginContext], whose scope is a SupervisorJob child of the
 * holder's scope. The context's first failure (`onAttach` or `onDetach` threw, a coroutine in its
 * scope threw, the plugin reported one, or its `frames` returned a rejected registration) sets its
 * `failure` and is emitted as [CameraKEvent.PluginFailed] with the plugin's id; later ones are only
 * logged. The camera and the other plugins carry on. That holds for any throwable but a fatal one
 * ([isFatal]): a `TODO()` body's NotImplementedError and a missing native library's LinkageError
 * are a plugin's failure, not the camera's.
 *
 * Every context reads the holder's flows, [dispatchers], [frames] and [recordingState]; nothing is
 * copied per plugin.
 */
internal class PluginHost(
    private val holder: CameraKStateHolder,
    private val holderScope: CoroutineScope,
    private val dispatchers: CameraKDispatchers,
    private val frames: FrameSource,
    private val recordingState: StateFlow<RecordingState>,
    private val emit: (CameraKEvent) -> Unit,
) {
    // A plugin coroutine that throws must not reach the platform's uncaught-exception handler, which
    // kills the host app on Android and iOS.
    @OptIn(InternalCameraKApi::class)
    private val failureHandler = CoroutineExceptionHandler { _, error ->
        CameraKLogger.e(TAG, "A plugin coroutine failed", error)
        // The scope is shared by every plugin, so it cannot tell which one launched the coroutine.
        emit(CameraKEvent.PluginFailed(SHARED_SCOPE_ID, CameraKError.Internal(error)))
    }

    /** The deprecated shared scope: a SupervisorJob child of the holder's scope. */
    val scope: CoroutineScope =
        CoroutineScope(holderScope.coroutineContext + SupervisorJob(holderScope.coroutineContext.job) + failureHandler)

    private val lock = SynchronizedObject()
    private val plugins = mutableListOf<CameraKPlugin>()
    private val contexts = mutableMapOf<CameraKPlugin, PluginContext>()
    private var attached = false
    private var closed = false

    /** Adds [plugin]; it attaches now if the first session has reached Ready, else when it does. */
    fun add(plugin: CameraKPlugin) {
        val context = synchronized(lock) {
            if (closed || plugin in plugins) return
            plugins += plugin
            if (attached) newContext(plugin) else null
        }
        context?.let(::attach)
    }

    fun remove(plugin: CameraKPlugin) {
        val context = synchronized(lock) {
            if (!plugins.remove(plugin)) return
            contexts.remove(plugin)
        }
        detach(plugin, context)
    }

    /** Attaches every plugin added so far, once: the first time a session reaches Ready. */
    fun attachPending() {
        val pending = synchronized(lock) {
            if (attached || closed) return
            attached = true
            plugins.map(::newContext)
        }
        pending.forEach(::attach)
    }

    /** Detaches every plugin, refuses new ones and cancels every coroutine plugins launched. */
    fun detachAll() {
        val current = synchronized(lock) {
            closed = true
            plugins.map { it to contexts[it] }.also {
                plugins.clear()
                contexts.clear()
            }
        }
        current.forEach { (plugin, context) -> detach(plugin, context) }
        scope.coroutineContext.job.cancelChildren()
    }

    // Guarded by lock.
    private fun newContext(plugin: CameraKPlugin): PluginContext = PluginContext(plugin).also { contexts[plugin] = it }

    @OptIn(InternalCameraKApi::class)
    private fun attach(context: PluginContext) {
        try {
            context.plugin.onAttach(context)
        } catch (e: Throwable) {
            if (e.isFatal()) throw e
            CameraKLogger.e(TAG, "Attaching a plugin failed; the camera carries on without it", e)
            context.fail(CameraKError.Internal(e))
        }
    }

    // A plugin removed before the first Ready has no context; it is still told it is detached, as in 1.x.
    @OptIn(InternalCameraKApi::class)
    private fun detach(plugin: CameraKPlugin, context: PluginContext?) {
        try {
            plugin.onDetach()
        } catch (e: Throwable) {
            if (e.isFatal()) throw e
            CameraKLogger.e(TAG, "Detaching a plugin failed", e)
            val error = CameraKError.Internal(e)
            if (context != null) context.fail(error) else emit(CameraKEvent.PluginFailed(plugin.id, error))
        } finally {
            context?.markDetached()
            context?.scope?.cancel()
        }
    }

    /** One attachment of [plugin]. The host is the only writer of [failure]. */
    internal inner class PluginContext(val plugin: CameraKPlugin) : CameraKPluginContext {
        private val _failure = MutableStateFlow<CameraKError?>(null)

        // Set once onDetach has returned; a failure after it belongs to no attachment.
        private val detached = atomic(false)
        override val failure: StateFlow<CameraKError?> = _failure.asStateFlow()

        @OptIn(InternalCameraKApi::class)
        override val scope: CoroutineScope = CoroutineScope(
            holderScope.coroutineContext + SupervisorJob(holderScope.coroutineContext.job) +
                CoroutineExceptionHandler { _, error ->
                    CameraKLogger.e(TAG, "A plugin coroutine failed", error)
                    fail(CameraKError.Internal(error))
                },
        )

        /** The holder, for the deprecated `onAttach(stateHolder)` the default `onAttach(context)` forwards to. */
        val stateHolder: CameraKStateHolder get() = holder

        override val dispatchers: CameraKDispatchers get() = this@PluginHost.dispatchers
        override val cameraState: StateFlow<CameraKState> get() = holder.cameraState
        override val uiState: StateFlow<CameraUIState> get() = holder.uiState
        override val capabilities: StateFlow<CameraCapabilities> get() = holder.capabilities
        override val deviceOrientation: StateFlow<DeviceOrientation> get() = holder.deviceOrientation
        override val recordingState: StateFlow<RecordingState> get() = this@PluginHost.recordingState
        override val captures: SharedFlow<CapturedImage> get() = holder.captures

        // The holder's source; only register is wrapped, so a frame costs nothing extra.
        override val frames: FrameSource = object : FrameSource {
            override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration =
                this@PluginHost.frames.register(spec, analyzer).also { registration ->
                    registration.rejection?.let { rejection ->
                        CameraKLogger.w(TAG, "A plugin's frame registration was rejected; the plugin is marked failed")
                        fail(rejection)
                    }
                }
        }

        override fun reportFailure(error: CameraKError) {
            CameraKLogger.w(TAG, "A plugin reported a failure", error.cause)
            fail(error)
        }

        /**
         * Sets [failure] and emits PluginFailed for this attachment's first failure; a later one is
         * only logged by its caller. After the detach it does neither and logs that it ignored one.
         */
        @OptIn(InternalCameraKApi::class)
        fun fail(error: CameraKError) {
            if (detached.value) {
                CameraKLogger.w(TAG, "A plugin failed after its detach; the failure is ignored", error.cause)
                return
            }
            if (_failure.compareAndSet(expect = null, update = error)) emit(CameraKEvent.PluginFailed(plugin.id, error))
        }

        fun markDetached() {
            detached.value = true
        }
    }

    private companion object {
        const val TAG = "CameraK"
        const val SHARED_SCOPE_ID = "pluginScope"
    }
}

/** The holder behind a context the holder made; null for any other context. */
internal val CameraKPluginContext.stateHolderOrNull: CameraKStateHolder?
    get() = (this as? PluginHost.PluginContext)?.stateHolder
