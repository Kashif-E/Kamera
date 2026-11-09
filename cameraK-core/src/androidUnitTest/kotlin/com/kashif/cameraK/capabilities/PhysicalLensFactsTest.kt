package com.kashif.cameraK.capabilities

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.util.SizeF
import com.kashif.cameraK.utils.CameraKLogger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.StreamConfigurationMapBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The enumeration reads what each physical lens of a logical multi-camera can do on its own, from its
 * own characteristics (w14): the sizes its stream configuration map outputs per use-case format, and
 * what it meters at a point. The Pixel 7's ultra-wide, camera 3 inside logical camera 0, outputs no
 * 4032x3024, reports a minimum focus distance of 0 and takes AE regions only.
 */
@RunWith(RobolectricTestRunner::class)
class PhysicalLensFactsTest {
    private class Logged(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?)

    private val logged = mutableListOf<Logged>()

    @Before
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable -> logged += Logged(level, message, throwable) }
    }

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun aPhysicalLensCarriesItsOwnOutputSizesAndMetering() {
        val lenses = LensEnumerator.snapshot(PixelReader())

        val facts = lenses.factsOf("3")
        assertEquals(
            LensOutputSizes(
                still = listOf(Size(4000, 3000), Size(1920, 1440)),
                preview = listOf(Size(1920, 1440), Size(1280, 960)),
                analysis = listOf(Size(4000, 3000), Size(640, 480)),
            ),
            facts.outputs,
        )
        assertEquals(PhysicalMetering(autoFocus = false, autoExposure = true, autoWhiteBalance = false), facts.metering)
        assertNull(lenses.lens("0").physical, "a camera CameraX binds as itself needs no facts")
    }

    @Test
    fun aLensThatFocusesButTakesNoAfRegionDoesNotFocusAtAPoint() {
        val lenses = LensEnumerator.snapshot(
            PixelReader(ultraWideMinFocus = 10f, ultraWideRegions = intArrayOf(1, 1, 0)),
        )

        val metering = lenses.factsOf("3").metering
        assertEquals(PhysicalMetering(autoFocus = false, autoExposure = true, autoWhiteBalance = true), metering)
    }

    @Test
    fun aLensThatFocusesAndTakesEveryRegionMetersEverything() {
        val lenses = LensEnumerator.snapshot(
            PixelReader(ultraWideMinFocus = 10f, ultraWideRegions = intArrayOf(1, 1, 1)),
        )

        assertEquals(PhysicalMetering.UNRESTRICTED, lenses.factsOf("3").metering)
    }

    @Test
    fun factsTheCharacteristicsLeaveOutAreNotHeldAgainstTheLens() {
        val lenses = LensEnumerator.snapshot(PixelReader(ultraWideMinFocus = null, ultraWideRegions = null))

        assertEquals(PhysicalMetering.UNRESTRICTED, lenses.factsOf("3").metering)
    }

    @Test
    fun readingTheOutputSizesFails_leavesThemUnreadAndLogsTheThrowable() {
        val failure = IllegalStateException("scripted stream configuration failure")
        val lenses = LensEnumerator.snapshot(PixelReader(outputFailure = failure))

        val facts = lenses.factsOf("3")
        assertNull(facts.outputs, "a lens whose sizes could not be read is not filtered")
        assertEquals(PhysicalMetering(autoFocus = false, autoExposure = true, autoWhiteBalance = false), facts.metering)
        val log = logged.single { it.throwable != null }
        assertSame(failure, log.throwable)
        assertEquals(CameraKLogger.Level.WARN, log.level)
        assertFalse(failure.message!! in log.message, "the throwable's message was formatted into the log")
    }

    private companion object {
        const val HAL_BLOB = 0x21
    }

    private fun List<AndroidLensDescriptor>.factsOf(id: String): PhysicalLensFacts =
        assertNotNull(lens(id).physical, "the enumeration read nothing of physical lens $id")

    private fun List<AndroidLensDescriptor>.lens(id: String): AndroidLensDescriptor =
        assertNotNull(firstOrNull { it.info.id == id }, "no lens $id in ${map { it.info.id }}")

    // Logical camera 0 with physical lenses 2 (the main lens) and 3 (the ultra-wide).
    private class PixelReader(
        private val ultraWideMinFocus: Float? = 0f,
        private val ultraWideRegions: IntArray? = intArrayOf(1, 0, 0),
        /** When set, reading the ultra-wide's output sizes throws it. */
        private val outputFailure: Throwable? = null,
    ) : Camera2Reader {
        override fun cameraIds(): Array<String> = arrayOf("0")

        private val byId = mutableMapOf<String, CameraCharacteristics>()

        override fun characteristics(id: String): CameraCharacteristics = byId.getOrPut(id) {
            when (id) {
                "0", "2" -> camera(focalLength = 6.81f, minFocus = 9.5f, regions = intArrayOf(1, 0, 1))
                else -> camera(2.23f, ultraWideMinFocus, ultraWideRegions, ultraWide = true)
            }
        }

        override fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
            if (characteristics === byId["0"]) setOf("2", "3") else emptySet()

        override fun outputSizes(characteristics: CameraCharacteristics): LensOutputSizes? {
            if (characteristics === byId["3"]) outputFailure?.let { throw it }
            return super.outputSizes(characteristics)
        }

        private fun camera(
            focalLength: Float,
            minFocus: Float?,
            regions: IntArray?,
            ultraWide: Boolean = false,
        ): CameraCharacteristics {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(focalLength))
                set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, SizeF(if (ultraWide) 5.6f else 9.8f, 7.4f))
                minFocus?.let { set(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, it) }
                regions?.let {
                    set(CameraCharacteristics.CONTROL_MAX_REGIONS_AE, it[0])
                    set(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB, it[1])
                    set(CameraCharacteristics.CONTROL_MAX_REGIONS_AF, it[2])
                }
                // The map is built from HAL formats, as the camera service reports them: BLOB is JPEG.
                if (ultraWide) {
                    set(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                        StreamConfigurationMapBuilder.newBuilder()
                            .addOutputSize(HAL_BLOB, Size(4000, 3000))
                            .addOutputSize(HAL_BLOB, Size(1920, 1440))
                            .addOutputSize(ImageFormat.PRIVATE, Size(1920, 1440))
                            .addOutputSize(ImageFormat.PRIVATE, Size(1280, 960))
                            .addOutputSize(ImageFormat.YUV_420_888, Size(4000, 3000))
                            .addOutputSize(ImageFormat.YUV_420_888, Size(640, 480))
                            .build(),
                    )
                }
            }
            return characteristics
        }
    }
}
