@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.plugin

import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A plugin that keeps every context it is attached with, as a migrated plugin does: the tests read
 * `failure` from it. [attached] runs after the context is kept, so a plugin that throws there has
 * already handed its context over.
 */
internal open class ContextPlugin(
    override val id: String = "ContextPlugin",
    override val needsFrames: Boolean = true,
    private val attached: (CameraKPluginContext) -> Unit = {},
) : CameraKPlugin {
    val contexts = mutableListOf<CameraKPluginContext>()
    var detaches = 0

    /** The latest attachment's context. */
    val context: CameraKPluginContext
        get() = assertNotNull(contexts.lastOrNull(), "the host never attached $id with a context")

    override fun onAttach(context: CameraKPluginContext) {
        contexts += context
        attached(context)
    }

    override fun onDetach() {
        detaches++
    }
}

/** A holder over [engine] with [plugins] attached at creation, and every event it emitted. */
internal class PluginSession(
    val holder: CameraKStateHolder,
    val engine: FakeCameraEngine,
    val environment: CameraKEnvironment,
    val events: List<CameraKEvent>,
) {
    val pluginFailures: List<CameraKEvent.PluginFailed> get() = events.filterIsInstance<CameraKEvent.PluginFailed>()
}

internal fun TestScope.pluginSession(
    vararg plugins: CameraKPlugin,
    engine: FakeCameraEngine = FakeCameraEngine(),
): PluginSession {
    val environment = cameraKTestEnvironment()
    val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> engine }, plugins.toList(), environment)
    val events = mutableListOf<CameraKEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
    return PluginSession(holder, engine, environment, events)
}

/** Starts the camera and runs until it is Ready, which attaches the plugins added so far. */
internal fun TestScope.startReady(session: PluginSession) {
    session.holder.start()
    runCurrent()
    assertIs<CameraKState.Ready>(session.holder.cameraState.value, "state is ${session.holder.cameraState.value}")
}
