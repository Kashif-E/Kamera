@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which camera a bind selects is decided from the lens snapshot alone, before CameraX is asked for a
 * provider, so it runs on the JVM without a camera.
 */
class CameraSelectorResolverTest {
    // A back logical camera "0" whose ultra-wide is the physical lens "2", a back telephoto CameraX
    // lists as "3", and a front camera "1".
    private val lenses = listOf(
        lens("0", CameraLens.BACK, CameraDeviceType.WIDE_ANGLE, isLogical = true),
        lens("2", CameraLens.BACK, CameraDeviceType.ULTRA_WIDE, physicalChildOf = "0"),
        lens("3", CameraLens.BACK, CameraDeviceType.TELEPHOTO),
        lens("1", CameraLens.FRONT, CameraDeviceType.WIDE_ANGLE),
    )

    @Test
    fun resolvesLensAndDeviceTypeWithoutACameraProvider() {
        val resolver = CameraSelectorResolver { lenses }

        assertEquals(
            ResolvedCamera.FacingDefault(CameraLens.BACK),
            resolver.resolve(CameraLens.BACK, CameraDeviceType.DEFAULT),
        )
        assertEquals(
            ResolvedCamera.FacingDefault(CameraLens.FRONT),
            resolver.resolve(CameraLens.FRONT, CameraDeviceType.WIDE_ANGLE),
        )
        assertEquals(
            ResolvedCamera.PhysicalLens(CameraLens.BACK, physicalId = "2", parentLogicalId = "0"),
            resolver.resolve(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE),
        )
        assertEquals(
            ResolvedCamera.TopLevel(CameraLens.BACK, cameraId = "3"),
            resolver.resolve(CameraLens.BACK, CameraDeviceType.TELEPHOTO),
        )
        assertEquals("2", resolver.resolve(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE).pinnedPhysicalId)
        assertNull(resolver.resolve(CameraLens.BACK, CameraDeviceType.TELEPHOTO).pinnedPhysicalId)
    }

    @Test
    fun deviceTypeTheFacingLacks_fallsBackToTheFacingDefault() {
        val resolver = CameraSelectorResolver { lenses }

        assertEquals(
            ResolvedCamera.FacingDefault(CameraLens.FRONT),
            resolver.resolve(CameraLens.FRONT, CameraDeviceType.TELEPHOTO),
            "a front telephoto request must open the front camera, not fail or pick a back lens",
        )
        assertEquals(
            ResolvedCamera.FacingDefault(CameraLens.BACK),
            resolver.resolve(CameraLens.BACK, CameraDeviceType.MACRO),
        )
    }

    @Test
    fun emptyEnumeration_isNotCachedSoTheNextResolveEnumeratesAgain() {
        // A transient camera-service failure reports no lenses; it must not disable lens switching.
        val answers = ArrayDeque(listOf(emptyList(), lenses))
        var enumerations = 0
        val resolver = CameraSelectorResolver {
            enumerations++
            answers.removeFirst()
        }

        assertEquals(
            ResolvedCamera.FacingDefault(CameraLens.BACK),
            resolver.resolve(CameraLens.BACK, CameraDeviceType.TELEPHOTO),
        )
        assertEquals(
            ResolvedCamera.TopLevel(CameraLens.BACK, "3"),
            resolver.resolve(CameraLens.BACK, CameraDeviceType.TELEPHOTO),
        )
        resolver.resolve(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE)
        assertEquals(2, enumerations, "a non-empty snapshot is enumerated once and then cached")
    }

    private fun lens(
        id: String,
        lens: CameraLens,
        type: CameraDeviceType,
        isLogical: Boolean = false,
        physicalChildOf: String? = null,
    ) = AndroidLensDescriptor(
        info = LensInfo(
            id = id,
            deviceType = type,
            lens = lens,
            minZoom = 1f,
            maxZoom = 8f,
            hasFlash = lens == CameraLens.BACK,
            isLogical = isLogical,
        ),
        isPhysicalChild = physicalChildOf != null,
        parentLogicalId = physicalChildOf,
    )
}
