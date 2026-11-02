package com.kashif.cameraK.capabilities

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * LensInfo is a plain class: a public data class cannot gain a field without breaking binary
 * compatibility, through copy and componentN. It keeps value equality.
 */
class LensInfoShapeTest {
    private fun lens(id: String = "0", isExternal: Boolean = false) = LensInfo(
        id = id,
        deviceType = CameraDeviceType.WIDE_ANGLE,
        lens = CameraLens.BACK,
        minZoom = 0.5f,
        maxZoom = 10f,
        hasFlash = true,
        isLogical = true,
        isExternal = isExternal,
    )

    @Test
    fun isAPlainClassWithValueEquality() {
        val generated = LensInfo::class.java.methods.map { it.name }
            .filter { it == "copy" || it.startsWith("copy$") || it.matches(Regex("component\\d+")) }
        assertTrue(generated.isEmpty(), "LensInfo still has data-class members: $generated")

        assertEquals(lens(), lens())
        assertEquals(lens().hashCode(), lens().hashCode())
        assertNotEquals(lens(), lens(id = "1"))
        assertNotEquals(lens(), lens(isExternal = true))
        assertTrue("isExternal=false" in lens().toString(), "toString names every field: ${lens()}")
    }
}
