package com.kashif.cameraK.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EngineRuntimeTest {
    @Test
    fun defaultBackoffDoublesToTheCap() {
        val delays = (1..8).map { EngineRuntime.Default.backoff.delayBefore(it) }

        assertEquals(
            listOf(
                250.milliseconds,
                500.milliseconds,
                1.seconds,
                2.seconds,
                4.seconds,
                4.seconds,
                4.seconds,
                4.seconds,
            ),
            delays,
        )
    }
}
