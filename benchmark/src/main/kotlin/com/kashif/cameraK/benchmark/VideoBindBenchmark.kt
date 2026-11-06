package com.kashif.cameraK.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The main-thread time of binding VideoCapture, CameraK's `CameraK.videoBind` section, which the
 * engine opens and closes on the main thread around the rebind. The Sample binds it when a recording
 * starts. Recorded on a Pixel 7 for the checklist.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class VideoBindBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun videoBindMainThreadTime() = rule.measureRepeated(
        packageName = SAMPLE_PACKAGE,
        metrics = listOf(TraceSectionMetric(CAMERAK_VIDEO_BIND, TraceSectionMetric.Mode.First)),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = {
            grantSamplePermissions()
            killProcess()
            openCameraScreen()
            selectVideoMode()
        },
    ) {
        pressShutter()
        awaitRecording(true)
        pressShutter()
        awaitRecording(false)
    }
}
