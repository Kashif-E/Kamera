package com.kashif.cameraK.state

import com.kashif.cameraK.plugin.CameraKPluginContext

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
     */
    fun onAttach(context: CameraKPluginContext)

    /** Called when the plugin is detached. Its context's scope is cancelled right after. */
    fun onDetach()
}
