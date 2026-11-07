package com.kashif.cameraK.capabilities

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(CameraKEngineApi::class)
class CameraCapabilitiesTest {

    private fun lens(id: String, type: CameraDeviceType, facing: CameraLens, hasFlash: Boolean = false) = LensInfo(
        id = id,
        deviceType = type,
        lens = facing,
        minZoom = 1f,
        maxZoom = 4f,
        hasFlash = hasFlash,
        isLogical = false,
    )

    private val caps = capabilitiesOf(
        listOf(
            lens("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK),
            lens("2", CameraDeviceType.ULTRA_WIDE, CameraLens.BACK),
            lens("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT),
        ),
    )

    @Test
    fun lenses_filtersByFacing() {
        assertEquals(listOf("0", "2"), caps.lenses(CameraLens.BACK).map { it.id })
        assertEquals(listOf("1"), caps.lenses(CameraLens.FRONT).map { it.id })
    }

    @Test
    fun availableDeviceTypes_isSetPerFacing() {
        assertEquals(
            setOf(CameraDeviceType.WIDE_ANGLE, CameraDeviceType.ULTRA_WIDE),
            caps.availableDeviceTypes(CameraLens.BACK),
        )
        assertEquals(setOf(CameraDeviceType.WIDE_ANGLE), caps.availableDeviceTypes(CameraLens.FRONT))
    }

    @Test
    fun empty_hasNoLenses() {
        assertTrue(CameraCapabilities.EMPTY.allLenses.isEmpty())
        assertTrue(CameraCapabilities.EMPTY.lenses(CameraLens.BACK).isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun capabilitiesOfClaimsNothingItWasNotGiven() {
        val given = capabilitiesOf(listOf(lens("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK)))

        assertFalse(given.supportsPauseResume, "pause and resume")
        assertEquals(emptySet(), given.supportedVideoQualities, "video qualities")
        assertFalse(given.supportsVideoBitrate, "video bitrate")
        assertEquals(setOf(ImageFormat.JPEG), given.supportedImageFormats, "image formats")
        assertEquals(
            setOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16),
            given.supportedAspectRatios,
            "aspect ratios",
        )
        assertFalse(given.supportsSquareCapture, "square capture")
        assertEquals(emptySet(), given.supportedAnalysisFormats, "analysis formats")
    }

    @Test
    fun flashAndTorchModesDeriveFromHasFlashPerId() {
        val given = capabilitiesOf(
            listOf(
                lens("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, hasFlash = true),
                lens("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, hasFlash = false),
            ),
        )

        assertEquals(setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON), given.supportedFlashModes("0"))
        assertEquals(setOf(TorchMode.OFF, TorchMode.ON), given.supportedTorchModes("0"))
        assertEquals(setOf(FlashMode.OFF), given.supportedFlashModes("1"))
        assertEquals(setOf(TorchMode.OFF), given.supportedTorchModes("1"))
    }

    @Test
    fun unknownCameraIdSupportsOnlyOff() {
        assertEquals(setOf(FlashMode.OFF), caps.supportedFlashModes("99"))
        assertEquals(setOf(TorchMode.OFF), caps.supportedTorchModes("99"))

        // A mode set for an id that is not one of the lenses claims nothing.
        val stray = full(flashModes = mapOf("0" to setOf(FlashMode.OFF), "99" to FlashMode.entries.toSet()))
        assertEquals(setOf(FlashMode.OFF), stray.supportedFlashModes("99"))
        assertEquals(setOf(TorchMode.OFF), CameraCapabilities.EMPTY.supportedTorchModes("0"))
    }

    @Test
    fun fullConstructorKeepsEveryMember() {
        val lenses = mutableListOf(lens("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, hasFlash = true))
        val flash = mutableMapOf("0" to mutableSetOf(FlashMode.OFF, FlashMode.ON))
        val qualities = mutableSetOf(VideoQuality.FHD)
        val given = full(
            lenses = lenses,
            flashModes = flash,
            torchModes = mapOf("0" to setOf(TorchMode.OFF, TorchMode.ON)),
            supportsPauseResume = true,
            supportedVideoQualities = qualities,
            supportsVideoBitrate = true,
            supportedAspectRatios = setOf(AspectRatio.RATIO_1_1),
            supportedImageFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG),
            supportedAnalysisFormats = setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888),
            supportsSquareCapture = true,
        )
        // The caller's collections change afterwards; the capabilities must not.
        lenses.clear()
        flash.getValue("0").add(FlashMode.AUTO)
        qualities.clear()

        assertEquals(listOf("0"), given.allLenses.map { it.id })
        assertEquals(setOf(FlashMode.OFF, FlashMode.ON), given.supportedFlashModes("0"))
        assertEquals(setOf(TorchMode.OFF, TorchMode.ON), given.supportedTorchModes("0"))
        assertTrue(given.supportsPauseResume)
        assertEquals(setOf(VideoQuality.FHD), given.supportedVideoQualities)
        assertTrue(given.supportsVideoBitrate)
        assertEquals(setOf(AspectRatio.RATIO_1_1), given.supportedAspectRatios)
        assertEquals(setOf(ImageFormat.JPEG, ImageFormat.PNG), given.supportedImageFormats)
        assertEquals(setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888), given.supportedAnalysisFormats)
        assertTrue(given.supportsSquareCapture)
    }

    @Test
    fun valueEquality() {
        assertEquals(full(), full())
        assertEquals(full().hashCode(), full().hashCode())
        assertEquals(full().toString(), full().toString())
        assertEquals(capabilitiesOf(caps.allLenses), caps)

        val variants = mapOf(
            "a lens" to full(lenses = listOf(lens("0", CameraDeviceType.TELEPHOTO, CameraLens.BACK, hasFlash = true))),
            "a flash mode" to full(flashModes = mapOf("0" to setOf(FlashMode.OFF))),
            "a torch mode" to full(torchModes = mapOf("0" to setOf(TorchMode.OFF))),
            "pause and resume" to full(supportsPauseResume = false),
            "a video quality" to full(supportedVideoQualities = setOf(VideoQuality.HD)),
            "video bitrate" to full(supportsVideoBitrate = false),
            "an aspect ratio" to full(supportedAspectRatios = setOf(AspectRatio.RATIO_4_3)),
            "an image format" to full(supportedImageFormats = setOf(ImageFormat.PNG)),
            "an analysis format" to full(supportedAnalysisFormats = setOf(FrameFormat.RGBA_8888)),
            "square capture" to full(supportsSquareCapture = false),
            "a focus point camera" to full(focusPointCameras = emptySet()),
            "an exposure range" to full(exposureRanges = mapOf("0" to -2..2)),
            "an exposure step" to full(exposureSteps = mapOf("0" to 0.5f)),
        )
        variants.forEach { (member, variant) ->
            assertNotEquals(full(), variant, "capabilities that differ only in $member compared equal")
        }
    }

    private fun full(
        lenses: List<LensInfo> = listOf(lens("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, hasFlash = true)),
        flashModes: Map<String, Set<FlashMode>> = mapOf("0" to setOf(FlashMode.OFF, FlashMode.ON)),
        torchModes: Map<String, Set<TorchMode>> = mapOf("0" to setOf(TorchMode.OFF, TorchMode.ON)),
        supportsPauseResume: Boolean = true,
        supportedVideoQualities: Set<VideoQuality> = setOf(VideoQuality.FHD),
        supportsVideoBitrate: Boolean = true,
        supportedAspectRatios: Set<AspectRatio> = setOf(AspectRatio.RATIO_16_9),
        supportedImageFormats: Set<ImageFormat> = setOf(ImageFormat.JPEG),
        supportedAnalysisFormats: Set<FrameFormat> = setOf(FrameFormat.YUV_420),
        supportsSquareCapture: Boolean = true,
        focusPointCameras: Set<String> = setOf("0"),
        exposureRanges: Map<String, IntRange> = mapOf("0" to -4..4),
        exposureSteps: Map<String, Float> = mapOf("0" to 0.25f),
    ) = CameraCapabilities(
        allLenses = lenses,
        flashModes = flashModes,
        torchModes = torchModes,
        supportsPauseResume = supportsPauseResume,
        supportedVideoQualities = supportedVideoQualities,
        supportsVideoBitrate = supportsVideoBitrate,
        supportedAspectRatios = supportedAspectRatios,
        supportedImageFormats = supportedImageFormats,
        supportedAnalysisFormats = supportedAnalysisFormats,
        supportsSquareCapture = supportsSquareCapture,
        focusPointCameras = focusPointCameras,
        exposureCompensationRanges = exposureRanges,
        exposureCompensationSteps = exposureSteps,
    )

    @Test
    fun focusPointAndExposureArePerCamera() {
        val given = CameraCapabilities(
            allLenses = caps.allLenses,
            flashModes = emptyMap(),
            torchModes = emptyMap(),
            supportsPauseResume = false,
            supportedVideoQualities = emptySet(),
            supportsVideoBitrate = false,
            supportedAspectRatios = emptySet(),
            supportedImageFormats = emptySet(),
            supportedAnalysisFormats = emptySet(),
            supportsSquareCapture = false,
            focusPointCameras = setOf("0"),
            exposureCompensationRanges = mapOf("0" to -6..6, "1" to -2..2),
            exposureCompensationSteps = mapOf("0" to 1f / 3, "1" to 0.5f),
        )

        assertTrue(given.supportsFocusPoint("0"), "the back wide camera")
        assertFalse(given.supportsFocusPoint("1"), "the front camera")
        assertEquals(-6..6, given.exposureCompensationRange("0"))
        assertEquals(1f / 3, given.exposureCompensationStep("0"))
        assertEquals(-2..2, given.exposureCompensationRange("1"))
        assertEquals(0.5f, given.exposureCompensationStep("1"))
        assertNull(given.exposureCompensationRange("2"), "the ultra-wide has no compensation")
        assertNull(given.exposureCompensationStep("2"), "no step without a range")
    }

    @Test
    fun focusPointAndExposureClaimNothingByDefaultOrForAnIdThatIsNotALens() {
        listOf("0", "1", "2").forEach { id ->
            assertFalse(caps.supportsFocusPoint(id), "capabilitiesOf claims a focus point for '$id'")
            assertNull(caps.exposureCompensationRange(id), "capabilitiesOf claims exposure for '$id'")
            assertNull(caps.exposureCompensationStep(id), "capabilitiesOf claims an exposure step for '$id'")
        }

        val stray = full(
            focusPointCameras = setOf("99"),
            exposureRanges = mapOf("99" to -1..1),
            exposureSteps = mapOf("99" to 1f),
        )
        assertFalse(stray.supportsFocusPoint("99"))
        assertNull(stray.exposureCompensationRange("99"))
        assertNull(stray.exposureCompensationStep("99"))
    }

    @Test
    fun anExposureRangeWithoutAStepOrAStepWithoutARangeIsRefused() {
        assertFailsWith<IllegalArgumentException>("a range without a step") {
            full(exposureRanges = mapOf("0" to -4..4), exposureSteps = emptyMap())
        }
        assertFailsWith<IllegalArgumentException>("a step without a range") {
            full(exposureRanges = emptyMap(), exposureSteps = mapOf("0" to 0.25f))
        }
    }

    @Test
    fun theFocusAndExposureMembersAreInToString() {
        val text = full().toString()
        listOf("focusPointCameras=", "exposureCompensationRanges=", "exposureCompensationSteps=").forEach {
            assertTrue(it in text, "toString omits $it: $text")
        }
    }
}
