package com.kashif.cameraK.controller

import android.graphics.Rect
import android.util.Size
import com.kashif.cameraK.controls.NormalizedPoint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tap on the preview's content rectangle reaches the buffer pixel the preview draws there (w13 G1):
 * the preview shows the ViewPort's crop of the buffer, turned by the preview's rotation and, for the
 * front camera, mirrored. The mapping undoes the mirror, then the rotation, then scales into the crop.
 */
@RunWith(RobolectricTestRunner::class)
class ContentPointToSurfacePointTest {
    private class Stream(val name: String, val resolution: Size, val crop: Rect)

    private val streams = listOf(
        Stream("4:3", Size(640, 480), Rect(0, 0, 640, 480)),
        Stream("16:9 native", Size(1920, 1080), Rect(0, 0, 1920, 1080)),
        Stream("16:9 cropped from 4:3", Size(640, 480), Rect(0, 60, 640, 420)),
        Stream("1:1 cropped from 4:3", Size(640, 480), Rect(80, 0, 560, 480)),
    )

    private val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)

    @Test
    fun cornersAndCentreMapInsideTheCropAtEveryRotationMirroredOrNot() {
        for (stream in streams) {
            for (rotation in ROTATIONS) {
                for (mirrored in listOf(false, true)) {
                    val label = "${stream.name}, rotation $rotation, mirrored $mirrored"
                    for ((x, y) in corners + (0.5f to 0.5f)) {
                        val mapped = map(stream, x, y, rotation, mirrored)
                        assertTrue(
                            mapped.x >= stream.crop.left &&
                                mapped.x <= stream.crop.right &&
                                mapped.y >= stream.crop.top &&
                                mapped.y <= stream.crop.bottom,
                            "$label: ($x, $y) mapped to $mapped, outside the crop ${stream.crop}",
                        )
                    }
                    assertEquals(
                        SurfacePoint(stream.crop.exactCenterX(), stream.crop.exactCenterY()),
                        map(stream, 0.5f, 0.5f, rotation, mirrored),
                        "$label: the centre",
                    )
                    val distinct = corners.map { (x, y) -> map(stream, x, y, rotation, mirrored) }.toSet()
                    assertEquals(4, distinct.size, "$label: the four corners map to four buffer corners: $distinct")
                }
            }
        }
    }

    // The buffer corner the display shows at its top left: the rotation turns the buffer clockwise onto the
    // display, and the mirror then swaps left and right.
    @Test
    fun theTopLeftCornerMapsToTheCropsDisplayedTopLeft() {
        val expected = mapOf(
            (0 to false) to Corner.LEFT_TOP,
            (90 to false) to Corner.LEFT_BOTTOM,
            (180 to false) to Corner.RIGHT_BOTTOM,
            (270 to false) to Corner.RIGHT_TOP,
            (0 to true) to Corner.RIGHT_TOP,
            (90 to true) to Corner.LEFT_TOP,
            (180 to true) to Corner.LEFT_BOTTOM,
            (270 to true) to Corner.RIGHT_BOTTOM,
        )
        for (stream in streams) {
            for ((key, corner) in expected) {
                val (rotation, mirrored) = key
                assertEquals(
                    corner.of(stream.crop),
                    map(stream, 0f, 0f, rotation, mirrored),
                    "${stream.name}, rotation $rotation, mirrored $mirrored",
                )
            }
        }
    }

    // One point off every axis, so a swapped or unflipped axis shows: on the 16:9 crop of a 4:3 buffer,
    // (0, 60)-(640, 420), the content point (0.25, 0.1).
    @Test
    fun anOffCentrePointLandsOnItsPixel() {
        val stream = streams[2]
        val expected = mapOf(
            (0 to false) to SurfacePoint(160f, 96f),
            (90 to false) to SurfacePoint(64f, 330f),
            (180 to false) to SurfacePoint(480f, 384f),
            (270 to false) to SurfacePoint(576f, 150f),
            (0 to true) to SurfacePoint(480f, 96f),
            (90 to true) to SurfacePoint(64f, 150f),
            (180 to true) to SurfacePoint(160f, 384f),
            (270 to true) to SurfacePoint(576f, 330f),
        )
        for ((key, point) in expected) {
            val (rotation, mirrored) = key
            assertEquals(point, map(stream, 0.25f, 0.1f, rotation, mirrored), "rotation $rotation, mirrored $mirrored")
        }
    }

    private fun map(stream: Stream, x: Float, y: Float, rotation: Int, mirrored: Boolean): SurfacePoint =
        contentPointToSurfacePoint(NormalizedPoint(x, y), stream.crop, stream.resolution, rotation, mirrored)

    private enum class Corner {
        LEFT_TOP,
        RIGHT_TOP,
        LEFT_BOTTOM,
        RIGHT_BOTTOM,
        ;

        fun of(crop: Rect): SurfacePoint = when (this) {
            LEFT_TOP -> SurfacePoint(crop.left.toFloat(), crop.top.toFloat())
            RIGHT_TOP -> SurfacePoint(crop.right.toFloat(), crop.top.toFloat())
            LEFT_BOTTOM -> SurfacePoint(crop.left.toFloat(), crop.bottom.toFloat())
            RIGHT_BOTTOM -> SurfacePoint(crop.right.toFloat(), crop.bottom.toFloat())
        }
    }

    private companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)
    }
}
