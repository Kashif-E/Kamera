package dev.kashifmehmood.camerak.capabilities

import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A device may list a physical lens of a logical multi-camera as a camera of its own too: the Galaxy S23's
 * ultra-wide is camera 2 in the camera id list and physical lens 2 inside logical camera 0. It is one lens,
 * offered once, as the camera CameraX opens directly.
 */
@RunWith(RobolectricTestRunner::class)
class PublicPhysicalLensTest {
    @Test
    fun aPhysicalLensTheDeviceAlsoListsAsACameraIsOfferedOnce() {
        val lenses = LensEnumerator.snapshot(GalaxyReader())

        assertEquals(listOf("0", "2", "5", "6"), lenses.map { it.info.id }.sorted())
        assertFalse(lenses.single { it.info.id == "2" }.isPhysicalChild, "the ultra-wide is offered as camera 2 itself")
    }

    @Test
    fun leavingOutTheSecondListingKeepsEveryLensType() {
        val lenses = LensEnumerator.snapshot(GalaxyReader())

        assertEquals(
            mapOf(
                "2" to CameraDeviceType.ULTRA_WIDE,
                "5" to CameraDeviceType.WIDE_ANGLE,
                "6" to CameraDeviceType.TELEPHOTO,
            ),
            lenses.filter { it.info.id != "0" }.associate { it.info.id to it.info.deviceType },
        )
    }

    // Back logical camera 0 with physical lenses 2 (ultra-wide), 5 (wide) and 6 (telephoto); 2 is also
    // listed on its own.
    private class GalaxyReader : Camera2Reader {
        private val byId = mutableMapOf<String, CameraCharacteristics>()

        override fun cameraIds(): Array<String> = arrayOf("0", "2")

        override fun characteristics(id: String): CameraCharacteristics = byId.getOrPut(id) {
            when (id) {
                "0" -> camera(floatArrayOf(2.2f, 6.3f, 6.9f), width = 9.8f)
                "2" -> camera(floatArrayOf(2.2f), width = 5.6f)
                "5" -> camera(floatArrayOf(6.3f), width = 9.8f)
                else -> camera(floatArrayOf(6.9f), width = 3.6f)
            }
        }

        override fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
            if (characteristics === byId["0"]) setOf("2", "5", "6") else emptySet()

        private fun camera(focalLengths: FloatArray, width: Float): CameraCharacteristics {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, focalLengths)
                set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, SizeF(width, width * 0.75f))
            }
            return characteristics
        }
    }
}
