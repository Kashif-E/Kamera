package com.kashif.cameraK.capabilities

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraCapabilitiesTest {

    private fun lens(id: String, type: CameraDeviceType, facing: CameraLens) = LensInfo(
        id = id,
        deviceType = type,
        lens = facing,
        minZoom = 1f,
        maxZoom = 4f,
        hasFlash = false,
        isLogical = false,
    )

    private val caps = CameraCapabilities(
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
}
