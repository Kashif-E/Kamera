package dev.kashifmehmood.camerak.frames

import dev.kashifmehmood.camerak.coroutines.isFatal
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

/**
 * Whether a throwable from a frame analyzer must propagate rather than be logged: the plugin host's
 * fatal rule, which this delegates to without adding one of its own. [AnalyzerLease.deliver] and
 * camerak-testing's `FakeFrameSource` both call it, so the fake catches exactly what the gate
 * catches; it is public only so the fake can reach it.
 */
@InternalCameraKApi
public fun Throwable.isFatalAnalyzerFailure(): Boolean = isFatal()
