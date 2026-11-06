package com.kashif.cameraK.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Shutter to file, at 4:3 and at 1:1. Android has no square sensor mode, so 1:1 captures 4:3 and
 * re-encodes a crop (android-1x1-viewport-reencode); the difference between the two runs is that
 * cost, which AspectRatio.RATIO_1_1's KDoc quotes once measured.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
class SquareCaptureBenchmark(private val square: Boolean) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun shutterToFileWithAndWithoutTheSquareViewPort() = rule.measureRepeated(
        packageName = SAMPLE_PACKAGE,
        metrics = listOf(TraceSectionMetric(CAMERAK_CAPTURE, TraceSectionMetric.Mode.First)),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = {
            grantSamplePermissions()
            killProcess()
            openCameraScreen()
            selectAspectRatio(if (square) "1:1" else "4:3")
        },
    ) {
        pressShutter()
        awaitCaptureSaved()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "square={0}")
        fun viewPorts(): List<Boolean> = listOf(false, true)
    }
}
