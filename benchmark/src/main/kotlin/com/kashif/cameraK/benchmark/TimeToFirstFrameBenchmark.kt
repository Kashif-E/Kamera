package com.kashif.cameraK.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Time to the first preview frame from a cold start, CameraK against bare CameraX. Both sections span
 * the same events: from just before the camera provider is requested to the preview's first completed
 * capture. Recorded for the checklist; the budget is 2.1's.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class TimeToFirstFrameBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Each iteration starts the Sample cold, then the bare-CameraX activity in a new process. */
    @Test
    fun againstBareCameraX() = rule.measureRepeated(
        packageName = SAMPLE_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(CAMERAK_FIRST_FRAME, TraceSectionMetric.Mode.First),
            TraceSectionMetric(BASELINE_FIRST_FRAME, TraceSectionMetric.Mode.First),
        ),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.COLD,
        iterations = STARTUP_ITERATIONS,
        setupBlock = { grantSamplePermissions() },
    ) {
        startActivityAndWait()
        awaitCameraReady()
        awaitFirstFrame()

        killProcess()
        dropKernelPageCache()
        startActivityAndWait(bareCameraXIntent())
        awaitFirstFrame()
    }
}
