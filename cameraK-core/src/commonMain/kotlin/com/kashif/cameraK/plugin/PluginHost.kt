package com.kashif.cameraK.plugin

import com.kashif.cameraK.coroutines.isFatal
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The configuration the holder gives its engine factory, derived from the plugins present at
 * creation. Until plugins declare whether they need frames, it is [config] unchanged.
 */
internal fun effectiveConfig(config: CameraConfiguration, plugins: List<CameraKPlugin>): CameraConfiguration = config

/**
 * A holder's plugins. Plugins added before the first session attach once, when that session first
 * reaches Ready; a plugin added after it attaches at once. One lock guards the list and the
 * attached flag, and `onAttach` and `onDetach` run outside it, so a plugin that calls back into the
 * holder cannot stall or deadlock another thread.
 *
 * A plugin that throws from `onAttach` or `onDetach` is logged and reported as
 * [CameraKEvent.PluginFailed]; the camera and the other plugins carry on. That holds for any
 * throwable but a fatal one ([isFatal]): a `TODO()` body's NotImplementedError and a missing
 * native library's LinkageError are a plugin's failure, not the camera's.
 */
internal class PluginHost(
    private val holder: CameraKStateHolder,
    private val holderScope: CoroutineScope,
    private val emit: suspend (CameraKEvent) -> Unit,
) {
    // A plugin coroutine that throws must not reach the platform's uncaught-exception handler, which
    // kills the host app on Android and iOS.
    private val failureHandler = CoroutineExceptionHandler { _, error ->
        CameraKLogger.e(TAG, "A plugin coroutine failed", error)
        report(error)
    }

    /** A SupervisorJob child of the holder's scope: one plugin's failure cancels nothing else. */
    val scope: CoroutineScope =
        CoroutineScope(holderScope.coroutineContext + SupervisorJob(holderScope.coroutineContext.job) + failureHandler)

    private val lock = SynchronizedObject()
    private val plugins = mutableListOf<CameraKPlugin>()
    private var attached = false
    private var closed = false

    /** Adds [plugin]; it attaches now if the first session has reached Ready, else when it does. */
    fun add(plugin: CameraKPlugin) {
        val attachNow = synchronized(lock) {
            if (closed || plugin in plugins) return
            plugins += plugin
            attached
        }
        if (attachNow) attach(plugin)
    }

    fun remove(plugin: CameraKPlugin) {
        val removed = synchronized(lock) { plugins.remove(plugin) }
        if (removed) detach(plugin)
    }

    /** Attaches every plugin added so far, once: the first time a session reaches Ready. */
    fun attachPending() {
        val pending = synchronized(lock) {
            if (attached || closed) return
            attached = true
            plugins.toList()
        }
        pending.forEach(::attach)
    }

    /** Detaches every plugin, refuses new ones and cancels every coroutine plugins launched. */
    fun detachAll() {
        val current = synchronized(lock) {
            closed = true
            plugins.toList().also { plugins.clear() }
        }
        current.forEach(::detach)
        scope.coroutineContext.job.cancelChildren()
    }

    private fun attach(plugin: CameraKPlugin) {
        try {
            plugin.onAttach(holder)
        } catch (e: Throwable) {
            if (e.isFatal()) throw e
            CameraKLogger.e(TAG, "Attaching a plugin failed; the camera carries on without it", e)
            report(e)
        }
    }

    private fun detach(plugin: CameraKPlugin) {
        try {
            plugin.onDetach()
        } catch (e: Throwable) {
            if (e.isFatal()) throw e
            CameraKLogger.e(TAG, "Detaching a plugin failed", e)
            report(e)
        }
    }

    private fun report(error: Throwable) {
        holderScope.launch { emit(CameraKEvent.PluginFailed(error)) }
    }

    private companion object {
        const val TAG = "CameraK"
    }
}
