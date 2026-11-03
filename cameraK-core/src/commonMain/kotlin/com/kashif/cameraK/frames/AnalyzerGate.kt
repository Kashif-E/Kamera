package com.kashif.cameraK.frames

/** Stub (F0'). */
internal class AnalyzerGate(
    private val maxFps: Float?,
    private val nanoTime: () -> Long,
    private val maxRetained: Int = 1,
) {
    val failures: Int get() = TODO("F0'")

    fun tryEnter(onReleased: () -> Unit = {}): AnalyzerLease? = TODO("F0'")
}

/** Stub (F0'). */
internal class AnalyzerLease internal constructor(private val gate: AnalyzerGate, private val onReleased: () -> Unit) {
    fun retain(): Unit = TODO("F0'")

    fun release(): Unit = TODO("F0'")

    fun deliver(analyze: () -> Unit): Unit = TODO("F0'")
}
