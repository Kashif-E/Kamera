package com.kashif.cameraK.controller

import androidx.tracing.Trace
import com.kashif.cameraK.engine.Tracer
import kotlinx.atomicfu.atomic

/**
 * The engine's trace sections, through androidx.tracing. An interval is an async section, because
 * the engine opens it in one coroutine step and closes it from a CameraX callback; a marker is an
 * empty section.
 */
internal object AndroidTracer : Tracer {
    private val cookies = atomic(0)

    override fun beginInterval(name: String): AutoCloseable {
        val cookie = cookies.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        return AutoCloseable { Trace.endAsyncSection(name, cookie) }
    }

    override fun marker(name: String) {
        Trace.beginSection(name)
        Trace.endSection()
    }
}
