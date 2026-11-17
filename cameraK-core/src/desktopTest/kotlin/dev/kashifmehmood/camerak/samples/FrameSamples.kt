package dev.kashifmehmood.camerak.samples

import dev.kashifmehmood.camerak.frames.AnalyzerGate
import dev.kashifmehmood.camerak.frames.AnalyzerLease
import dev.kashifmehmood.camerak.frames.CameraFrame
import dev.kashifmehmood.camerak.frames.FrameAnalyzer
import dev.kashifmehmood.camerak.frames.FrameSpec
import dev.kashifmehmood.camerak.plugin.CameraKPluginContext
import dev.kashifmehmood.camerak.plugin.launchPerSession
import dev.kashifmehmood.camerak.state.CameraKPlugin
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
