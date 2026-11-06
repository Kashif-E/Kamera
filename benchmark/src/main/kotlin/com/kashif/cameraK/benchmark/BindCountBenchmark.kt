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
 * How often CameraX binds, counted from its own trace section. A cold start binds once, with the
 * frame analysis the Sample's plugins declare at creation in that one bind (Decision 14), and an
 * analyzer registering or unregistering later rebinds nothing.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class BindCountBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Expected: the bind count is 1 in every iteration, and the first frame arrives inside the trace. */
    @Test
    fun exactlyOneBindToFirstFrame() = rule.measureRepeated(
        packageName = SAMPLE_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(CAMERAX_BIND, TraceSectionMetric.Mode.Count),
            TraceSectionMetric(CAMERAK_FIRST_FRAME, TraceSectionMetric.Mode.First),
        ),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.COLD,
        iterations = STARTUP_ITERATIONS,
        setupBlock = { grantSamplePermissions() },
    ) {
        startActivityAndWait()
        awaitCameraReady()
        awaitFirstFrame()
    }

    /** Expected: the bind count is 0. The QR Scanner switch unregisters the QR plugin's analyzer, then registers it. */
    @Test
    fun zeroRebindsOnAnalyzerRegisterAndUnregister() = rule.measureRepeated(
        packageName = SAMPLE_PACKAGE,
        metrics = listOf(TraceSectionMetric(CAMERAX_BIND, TraceSectionMetric.Mode.Count)),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = {
            grantSamplePermissions()
            killProcess()
            openCameraScreen()
            openSettings()
        },
    ) {
        toggleQrScanner()
        toggleQrScanner()
    }
}
