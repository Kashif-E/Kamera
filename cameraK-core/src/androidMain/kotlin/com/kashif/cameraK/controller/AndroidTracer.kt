package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.Tracer

/** The engine's trace sections, through androidx.tracing. Red stub: A1' implements it. */
internal object AndroidTracer : Tracer {
    override fun beginInterval(name: String): AutoCloseable = TODO("A1'")

    override fun marker(name: String): Unit = TODO("A1'")
}
