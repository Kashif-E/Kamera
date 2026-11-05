package com.kashif.cameraK.state

import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.stateHolderOrNull
import com.kashif.cameraK.utils.CameraKLogger

/**
 * A camera plugin: QR scanning, OCR, frame analysis, saving captures. The holder attaches it once
 * the first session reaches Ready (at once when the plugin is added later) and detaches it when it
 * is removed or the holder closes.
 *
 * A plugin works through its [CameraKPluginContext]: its own scope, the holder's state flows, the
 * frame source and its own failure. The context has no commands; a plugin cannot shut the camera
 * down or write the holder's state.
 *
 * @example
 * ```kotlin
 * class LumaPlugin : CameraKPlugin {
 *     override fun onAttach(context: CameraKPluginContext) {
 *         context.launchPerSession {
 *             context.frames.register(FrameSpec(maxFps = 5f)) { frame -> measure(frame) }
 *                 .use { awaitCancellation() }
 *         }
 *     }
 *
 *     override fun onDetach() = Unit
 * }
 * ```
 */
interface CameraKPlugin {
    /** Names this plugin in [CameraKEvent.PluginFailed]. Defaults to the class's simple name. */
    val id: String get() = this::class.simpleName ?: "CameraKPlugin"

    /**
     * True for a plugin that registers frame analyzers. A holder created with such a plugin binds
     * frame analysis at start (`CameraConfiguration.frameAnalysis`), so the first registration
     * needs no rebind. A plugin that never reads frames returns false.
     */
    val needsFrames: Boolean get() = true

    /**
     * Called once per attachment, outside any lock, with a new [context]. Throwing here sets that
     * context's `failure` and emits [CameraKEvent.PluginFailed]; the camera and the other plugins
     * carry on.
     *
     * The default forwards to the deprecated `onAttach(stateHolder)`, so a 1.x plugin keeps working.
     */
    fun onAttach(context: CameraKPluginContext) {
        val holder = checkNotNull(context.stateHolderOrNull) {
            "A plugin that implements only onAttach(stateHolder) needs a context its holder made"
        }
        @Suppress("DEPRECATION")
        onAttach(holder)
    }

    /**
     * The 1.x attach. The holder calls [onAttach] with a context, whose default calls this. Its own
     * default does nothing but log a warning, which only a plugin that overrides neither overload
     * reaches.
     */
    @Deprecated("Implement onAttach(context: CameraKPluginContext); the context gives the plugin its scope and state.")
    fun onAttach(stateHolder: CameraKStateHolder) {
        CameraKLogger.w("CameraK", "A plugin overrides neither onAttach overload, so attaching it does nothing")
    }

    /** Called when the plugin is detached. Its context's scope is cancelled right after. */
    fun onDetach()
}
