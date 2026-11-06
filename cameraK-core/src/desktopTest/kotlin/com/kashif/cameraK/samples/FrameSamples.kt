package com.kashif.cameraK.samples

import com.kashif.cameraK.frames.AnalyzerGate
import com.kashif.cameraK.frames.AnalyzerLease
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.launchPerSession
import com.kashif.cameraK.state.CameraKPlugin
import kotlinx.coroutines.awaitCancellation

// KDoc samples for camerak-core's frame and plugin API, referenced with @sample. They live here so
// the compiler checks them.

internal fun cameraKPluginSample(measure: (CameraFrame) -> Unit): CameraKPlugin = object : CameraKPlugin {
    override fun onAttach(context: CameraKPluginContext) {
        context.launchPerSession {
            context.frames.register(FrameSpec(maxFps = 5f)) { frame -> measure(frame) }
                .use { awaitCancellation() }
        }
    }

    override fun onDetach() = Unit
}

internal fun launchPerSessionSample(context: CameraKPluginContext, decode: (CameraFrame) -> Unit) {
    // In the plugin's onAttach(context):
    context.launchPerSession {
        context.frames.register(FrameSpec(maxFps = 10f)) { frame -> decode(frame) }.use { awaitCancellation() }
    }
}

/**
 * A platform frame source's delivery: [copyInto] copies the platform frame into [slot]'s memory,
 * and the copy's `retain()` calls `lease.retain()`, whose `RetainedFrame.close()` releases that
 * handle.
 */
internal fun analyzerGateSample(
    gate: AnalyzerGate,
    slot: AutoCloseable,
    analyzer: FrameAnalyzer,
    copyInto: (AutoCloseable, AnalyzerLease) -> CameraFrame,
) {
    // Skip the frame: the analyzer is busy, throttled or at maxRetained.
    val lease = gate.tryEnter(onReleased = { slot.close() }) ?: return
    val frame = copyInto(slot, lease)
    lease.deliver { analyzer.analyze(frame) }
}
