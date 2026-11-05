package com.kashif.cameraK.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A zero or negative size is rejected when the configuration is built, with an
 * IllegalArgumentException that names the field (w8 CF' item 4), rather than reaching an engine
 * that cannot use it.
 */
class ResolutionValidationTest {
    private val unusable = listOf(Resolution(0, 0), Resolution(0, 480), Resolution(640, 0), Resolution(-4, 3))

    @Test
    fun zeroOrNegativeSizeIsRejectedWhenTheConfigurationIsBuilt() {
        for (size in unusable) {
            val analysis = assertFailsWith<IllegalArgumentException>("analysisResolution $size was accepted") {
                CameraConfiguration { analysisResolution = size }
            }
            assertTrue("analysisResolution" in analysis.message.orEmpty(), "the message: ${analysis.message}")
            val target = assertFailsWith<IllegalArgumentException>("targetResolution $size was accepted") {
                CameraConfiguration { targetResolution = size }
            }
            assertTrue("targetResolution" in target.message.orEmpty(), "the message: ${target.message}")
            assertFailsWith<IllegalArgumentException>("a copy with analysisResolution $size was accepted") {
                CameraConfiguration {}.copy { analysisResolution = size }
            }
        }

        val config = CameraConfiguration {
            analysisResolution = Resolution(1, 1)
            targetResolution = Resolution(1920, 1080)
        }
        assertEquals(Resolution(1, 1), config.analysisResolution)
        assertEquals<Any?>(Resolution(1920, 1080), config.targetResolution)
    }
}
