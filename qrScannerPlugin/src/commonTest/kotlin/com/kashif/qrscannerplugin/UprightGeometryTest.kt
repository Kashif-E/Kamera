package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Turning a frame upright by its stated clockwise rotation (#168): the pixels, a rectangle, and a
 * point found in the upright image mapped back to frame pixels.
 */
class UprightGeometryTest {
    // A 3 x 2 image:  a b c
    //                 d e f
    private val image = "abcdef".encodeToByteArray()

    private fun turned(rotation: Int): String {
        val geometry = UprightGeometry(3, 2, rotation)
        val out = ByteArray(6)
        geometry.turnUpright(image, out)
        return out.decodeToString().chunked(geometry.uprightWidth).joinToString("/")
    }

    @Test
    fun pixelsAreTurnedClockwiseByTheRotation() {
        assertEquals("abc/def", turned(0))
        assertEquals("da/eb/fc", turned(90))
        assertEquals("fed/cba", turned(180))
        assertEquals("cf/be/ad", turned(270))
    }

    @Test
    fun aRotationIsReadAsAQuarterTurn() {
        assertEquals(1, UprightGeometry(3, 2, 450).quarterTurns)
        assertEquals(3, UprightGeometry(3, 2, -90).quarterTurns)
    }

    @Test
    fun aRectangleTurnsWithTheImage() {
        // In a 100 x 50 frame, [10, 5, 60, 45) turned 90 degrees lies in a 50 x 100 image.
        val geometry = UprightGeometry(100, 50, 90)

        assertEquals(50 to 100, geometry.uprightWidth to geometry.uprightHeight)
        assertEquals(listOf(5, 10, 45, 60), geometry.toUpright(FrameRect(10, 5, 60, 45)).edges())
    }

    @Test
    fun uprightPointsMapBackToTheFramePixelsTheyCameFrom() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = UprightGeometry(100, 50, rotation)
            val x = 30f
            val y = 12f
            val upright = floatArrayOf(geometry.uprightX(x, y), geometry.uprightY(x, y))

            assertContentEquals(listOf(30, 12, 31, 13), geometry.toImage(upright).edges(), "at $rotation degrees")
        }
    }

    @Test
    fun aLineFoundUprightIsALineInTheFrame() {
        // A 1D barcode's two end points, across the upright image of a portrait frame.
        val geometry = UprightGeometry(40, 80, 90)

        assertEquals(listOf(20, 10, 21, 70), geometry.toImage(floatArrayOf(10f, 20f, 70f, 20f)).edges())
    }
}
