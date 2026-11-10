@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.internal.signpost.camerak_signpost_begin
import com.kashif.cameraK.internal.signpost.camerak_signpost_end
import com.kashif.cameraK.internal.signpost.camerak_signpost_event
import com.kashif.cameraK.internal.signpost.camerak_signpost_id
import com.kashif.cameraK.internal.signpost.camerak_signpost_log
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi

/** The iOS engine's trace names beyond the shared ones in TraceNames. */
internal object AppleTraceNames {
    /** Marks a lens switch whose input the session refused, inside its `CameraK.lensSwitch` interval. */
    const val LENS_SWITCH_FAILED = "CameraK.lensSwitch.failed"

    /**
     * Marks a lens switch whose first frame from the new camera never came, inside its
     * `CameraK.lensSwitch` interval, which then ends at the engine's stop, close, failure or next switch.
     */
    const val LENS_SWITCH_NO_FRAME = "CameraK.lensSwitch.noFrame"
}

/**
 * The engine's production Tracer: each interval is an os_signpost interval and each marker an
 * os_signpost event, under the "CameraK" name, labelled with the trace name, so Instruments' Points
 * of Interest and os_signpost tracks show them on a device.
 */
internal fun signpostTracer(): Tracer = SignpostTracer

private object SignpostTracer : Tracer {
    private val log = camerak_signpost_log()

    override fun beginInterval(name: String): AutoCloseable {
        val id = camerak_signpost_id(log)
        camerak_signpost_begin(log, id, name)
        val ended = atomic(false)
        return AutoCloseable {
            if (ended.compareAndSet(expect = false, update = true)) camerak_signpost_end(log, id, name)
        }
    }

    override fun marker(name: String) = camerak_signpost_event(log, name)
}
