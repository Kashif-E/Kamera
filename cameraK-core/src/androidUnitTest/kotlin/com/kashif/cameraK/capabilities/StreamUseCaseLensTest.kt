package com.kashif.cameraK.capabilities

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

    @Test
    @Config(sdk = [32])
    fun belowAndroid13NoStreamUseCaseIsSetAndThePhysicalLensesAreOffered() {
        val lenses = LensEnumerator.snapshot(EmulatorFrontReader(physicalUseCases = null))

        assertEquals(setOf("1", "3", "4"), lenses.map { it.info.id }.toSet())
    }

    // Logical front camera 1 with physical lenses 3 (wide) and 4 (telephoto), as the API 34 emulator has.
    private class EmulatorFrontReader(private val physicalUseCases: LongArray?) : Camera2Reader {
        private val byId = mutableMapOf<String, CameraCharacteristics>()

        override fun cameraIds(): Array<String> = arrayOf("1")

        override fun characteristics(id: String): CameraCharacteristics = byId.getOrPut(id) {
            when (id) {
                "1" -> camera(4.4f, LOGICAL_USE_CASES)
                "3" -> camera(4.4f, physicalUseCases)
                else -> camera(13.0f, physicalUseCases)
            }
        }

        override fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
            if (characteristics === byId["1"]) setOf("3", "4") else emptySet()

        private fun camera(focalLength: Float, useCases: LongArray?): CameraCharacteristics {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_FRONT)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(focalLength))
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
