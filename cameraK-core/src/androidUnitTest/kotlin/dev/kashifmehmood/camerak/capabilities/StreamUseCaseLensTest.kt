package dev.kashifmehmood.camerak.capabilities

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.SizeF
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import kotlin.test.assertEquals

/**
 * From Android 13 CameraX gives each stream the stream use case its logical camera lists (PREVIEW,
 * STILL_CAPTURE), except in zero-shutter-lag mode. The camera service checks a stream pinned to a
 * physical lens against that lens's own list, so a lens that lists none of them fails every session
 * but SPEED's with onConfigureFailed ("stream use case 1 not supported": the emulator's front
 * telephoto, physical camera 4 inside logical camera 1). Such a lens is not offered.
 */
@RunWith(RobolectricTestRunner::class)
class StreamUseCaseLensTest {
    @Test
    @Config(sdk = [34])
    fun aPhysicalLensWithoutItsLogicalCamerasStreamUseCasesIsNotOffered() {
        val lenses = LensEnumerator.snapshot(EmulatorFrontReader(physicalUseCases = null))

        assertEquals(listOf("1"), lenses.map { it.info.id })
    }

    @Test
    @Config(sdk = [34])
    fun aPhysicalLensListingItsLogicalCamerasStreamUseCasesIsOffered() {
        val lenses = LensEnumerator.snapshot(EmulatorFrontReader(physicalUseCases = LOGICAL_USE_CASES))

        assertEquals(setOf("1", "3", "4"), lenses.map { it.info.id }.toSet())
    }

    // CameraX sets PREVIEW, STILL_CAPTURE, VIDEO_RECORD, PREVIEW_VIDEO_STILL or VIDEO_CALL only: a lens
    // without the logical camera's CROPPED_RAW or a vendor use case streams.
    @Test
    @Config(sdk = [34])
    fun aPhysicalLensLackingOnlyUseCasesCameraXNeverSetsIsOffered() {
        val lenses = LensEnumerator.snapshot(
            EmulatorFrontReader(
                physicalUseCases = LOGICAL_USE_CASES,
                logicalUseCases =
                LOGICAL_USE_CASES + 6L + 0x10000L,
            ),
        )

        assertEquals(setOf("1", "3", "4"), lenses.map { it.info.id }.toSet())
    }

    // The types are read from every lens of the facing, so leaving one out relabels none of the others.
    @Test
    @Config(sdk = [34])
    fun leavingOutALensKeepsTheOthersTypes() {
        val all = LensEnumerator.snapshot(EmulatorFrontReader(physicalUseCases = LOGICAL_USE_CASES))
        val withoutWide = LensEnumerator.snapshot(
            EmulatorFrontReader(physicalUseCases = LOGICAL_USE_CASES, wideUseCases = null),
        )

        assertEquals(listOf("1", "4"), withoutWide.map { it.info.id }.sorted())
        for (lens in withoutWide) {
            assertEquals(all.single { it.info.id == lens.info.id }.info.deviceType, lens.info.deviceType, lens.info.id)
        }
    }

    @Test
    @Config(sdk = [32])
    fun belowAndroid13NoStreamUseCaseIsSetAndThePhysicalLensesAreOffered() {
        val lenses = LensEnumerator.snapshot(EmulatorFrontReader(physicalUseCases = null))

        assertEquals(setOf("1", "3", "4"), lenses.map { it.info.id }.toSet())
    }

    // Logical front camera 1, of both focal lengths, with physical lenses 3 (wide) and 4 (telephoto), as
    // the API 34 emulator has.
    private class EmulatorFrontReader(
        private val physicalUseCases: LongArray?,
        private val logicalUseCases: LongArray = LOGICAL_USE_CASES,
        private val wideUseCases: LongArray? = physicalUseCases,
    ) : Camera2Reader {
        private val byId = mutableMapOf<String, CameraCharacteristics>()

        override fun cameraIds(): Array<String> = arrayOf("1")

        override fun characteristics(id: String): CameraCharacteristics = byId.getOrPut(id) {
            when (id) {
                "1" -> camera(floatArrayOf(4.4f, 13.0f), logicalUseCases)
                "3" -> camera(floatArrayOf(4.4f), wideUseCases)
                else -> camera(floatArrayOf(13.0f), physicalUseCases)
            }
        }

        override fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
            if (characteristics === byId["1"]) setOf("3", "4") else emptySet()

        private fun camera(focalLengths: FloatArray, useCases: LongArray?): CameraCharacteristics {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_FRONT)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, focalLengths)
                set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, SizeF(5.6f, 4.2f))
                // The key is API 33's: an older device has no such entry.
                val hasKey = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                if (hasKey && useCases != null) set(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES, useCases)
            }
            return characteristics
        }
    }

    private companion object {
        // DEFAULT, PREVIEW, STILL_CAPTURE, VIDEO_RECORD, PREVIEW_VIDEO_STILL, VIDEO_CALL.
        val LOGICAL_USE_CASES = longArrayOf(0, 1, 2, 3, 4, 5)
    }
}
