package com.kashif.cameraK.controller

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.internal.Camera2CameraInfoImpl
import androidx.camera.camera2.internal.compat.CameraManagerCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
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
 * A camera the lens snapshot lists but CameraX does not offer falls back to the facing's cameras.
 * CameraX hands each filter an unmodifiable view and then trims the filter's result in place, so a
 * filter that returns that view unchanged makes the selection throw instead of falling back.
 */
@RunWith(RobolectricTestRunner::class)
class CameraSelectorFallbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cameras() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        shadowOf(manager).addCamera("0", characteristics(CameraCharacteristics.LENS_FACING_BACK))
        shadowOf(manager).addCamera("1", characteristics(CameraCharacteristics.LENS_FACING_FRONT))
    }

    @Test
    fun aCameraIdCameraXDoesNotOffer_fallsBackToTheFacingsCamera() {
        val selected = selectedBy(ResolvedCamera.TopLevel(CameraLens.BACK, cameraId = "7"))

        assertEquals(listOf("0"), selected)
    }

    @Test
    fun aPhysicalLensWhoseParentCameraXDoesNotOffer_fallsBackToTheFacingsCamera() {
        val selected = selectedBy(ResolvedCamera.PhysicalLens(CameraLens.BACK, physicalId = "4", parentLogicalId = "9"))

        assertEquals(listOf("0"), selected)
    }

    // The ids of the cameras [camera]'s selector keeps, out of the two CameraX offers.
    private fun selectedBy(camera: ResolvedCamera): List<String> {
        val cameraManager = CameraManagerCompat.from(context)
        val offered: List<CameraInfo> = listOf("0", "1").map { Camera2CameraInfoImpl(it, cameraManager) }
        return camera.toCameraSelector().filter(offered).map { Camera2CameraInfo.from(it).cameraId }
    }

    private fun characteristics(facing: Int): CameraCharacteristics {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
            set(CameraCharacteristics.LENS_FACING, facing)
            set(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
            )
        }
        return characteristics
    }
}
