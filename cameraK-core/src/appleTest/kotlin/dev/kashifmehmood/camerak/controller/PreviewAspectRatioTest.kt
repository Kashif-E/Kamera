@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class, InternalCameraKApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.contract.AppleEngineDriver
import dev.kashifmehmood.camerak.engine.previewAspectRatio
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.enums.DeviceOrientation
import dev.kashifmehmood.camerak.enums.previewAspectRatio
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.utils.InternalCameraKApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `:cameraK`'s common overlay layout reads the iOS engine's aspect ratio through the same accessor as
 * Android's, and turns it with the interface orientation the preview layer's host reads, so the
 * overlay's content rectangle and the preview layer's box are one box (design §12.4 "W12", Y-overlay
 * on iOS).
 */
class PreviewAspectRatioTest {
    private val driver = AppleEngineDriver()

    @Test
    fun appleSourceReportsTheConfiguredAspectRatio() = driver.runCase {
        for (ratio in AspectRatio.entries) {
            val engine = driver.createEngine(CameraConfiguration { aspectRatio = ratio })
            assertEquals(ratio, engine.preview.previewAspectRatio(), "the engine's preview source at $ratio")
            engine.close()
        }
    }

    /** Characterization: green before the slice, it ties the layer's box to the overlay's ratio table. */
    @Test
    fun layerBoxHasTheCommonRatio() {
        // Before the host has a window scene (null), the host's shape stands in for the orientation.
        val orientations: List<UIInterfaceOrientation?> = listOf(
            UIInterfaceOrientationPortrait,
            UIInterfaceOrientationPortraitUpsideDown,
            UIInterfaceOrientationLandscapeLeft,
            UIInterfaceOrientationLandscapeRight,
            null,
        )
        val hosts = listOf(390.0 to 844.0, 844.0 to 390.0)
        for (ratio in AspectRatio.entries) {
            for (interfaceOrientation in orientations) {
                for ((width, height) in hosts) {
                    val common = when (interfaceOrientation) {
                        UIInterfaceOrientationPortrait, UIInterfaceOrientationPortraitUpsideDown ->
                            DeviceOrientation.PORTRAIT
                        null -> if (height > width) DeviceOrientation.PORTRAIT else DeviceOrientation.LANDSCAPE_LEFT
                        else -> DeviceOrientation.LANDSCAPE_LEFT
                    }
                    val frame = previewFrame(CGRectMake(0.0, 0.0, width, height), ratio, interfaceOrientation)
                    val actual = frame.useContents { size.width / size.height }
                    val expected = ratio.previewAspectRatio(common).toDouble()
                    // Float precision: the common ratio is a Float, the layer's sides are CGFloat Doubles.
                    assertEquals(
                        expected,
                        actual,
                        expected * RELATIVE,
                        "$ratio under interface orientation $interfaceOrientation in a ${width}x$height host",
                    )
                }
            }
        }
    }

    private companion object {
        const val RELATIVE = 1e-4
    }
}
