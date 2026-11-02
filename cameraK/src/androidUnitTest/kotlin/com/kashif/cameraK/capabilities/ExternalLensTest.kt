package com.kashif.cameraK.capabilities

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.CameraLens
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import kotlin.test.assertEquals

/**
 * Chromebooks, tablets and TVs report USB cameras as LENS_FACING_EXTERNAL. Such a camera faces
 * neither way, CameraX never selects it for a back-facing request, and it must not stand in for a
 * back lens.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalLensTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cameras() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        shadowOf(manager).addCamera("0", characteristics(CameraCharacteristics.LENS_FACING_BACK, focalLengthMm = 4.4f))
        shadowOf(manager).addCamera("1", characteristics(CameraCharacteristics.LENS_FACING_FRONT, focalLengthMm = 2.2f))
        shadowOf(manager).addCamera(
            "5",
            characteristics(CameraCharacteristics.LENS_FACING_EXTERNAL, focalLengthMm = 12f),
        )
    }

    @Test
    fun externalCamera_isReportedAsExternalAndBuiltInOnesAreNot() {
        val external = LensEnumerator.snapshot(context).associate { it.info.id to it.info.isExternal }

        assertEquals(mapOf("0" to false, "1" to false, "5" to true), external)
    }

    @Test
    fun backLenses_doNotIncludeTheExternalCamera() {
        val capabilities = CameraCapabilities(LensEnumerator.snapshot(context).map { it.info })

        assertEquals(listOf("0"), capabilities.lenses(CameraLens.BACK).map { it.id })
        assertEquals(listOf("1"), capabilities.lenses(CameraLens.FRONT).map { it.id })
    }

    private fun characteristics(facing: Int, focalLengthMm: Float): CameraCharacteristics {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
            set(CameraCharacteristics.LENS_FACING, facing)
            set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(focalLengthMm))
            set(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
            )
        }
        return characteristics
    }
}
