package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The geometry of turning a [width] x [height] image upright by [rotationDegrees] clockwise (0, 90,
 * 180 or 270; anything else is read modulo 360 and rounded down to a quarter turn), in continuous
 * pixel coordinates with the origin at the top left. Decoders read upright pixels, so a 1D barcode
 * in a portrait frame lies along their scan lines, and map what they find back to frame pixels.
 */
internal class UprightGeometry(val width: Int, val height: Int, rotationDegrees: Int) {
    val quarterTurns: Int = ((rotationDegrees % 360 + 360) % 360) / 90

    val uprightWidth: Int get() = if (quarterTurns % 2 == 0) width else height
    val uprightHeight: Int get() = if (quarterTurns % 2 == 0) height else width

    /** The upright x of the image point ([x], [y]). */
    fun uprightX(x: Float, y: Float): Float = when (quarterTurns) {
        1 -> height - y
        2 -> width - x
        3 -> y
        else -> x
    }

    /** The upright y of the image point ([x], [y]). */
    fun uprightY(x: Float, y: Float): Float = when (quarterTurns) {
        1 -> x
        2 -> height - y
        3 -> width - x
        else -> y
    }

    /** The image x of the upright point ([ux], [uy]). */
    fun imageX(ux: Float, uy: Float): Float = when (quarterTurns) {
        1 -> uy
        2 -> width - ux
        3 -> width - uy
        else -> ux
    }

    /** The image y of the upright point ([ux], [uy]). */
    fun imageY(ux: Float, uy: Float): Float = when (quarterTurns) {
        1 -> height - ux
        2 -> height - uy
        3 -> ux
        else -> uy
    }

    /** [rect] of the image, upright. */
    fun toUpright(rect: FrameRect): FrameRect = bounds(
        floatArrayOf(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
        ::uprightX,
        ::uprightY,
    )

    /** The image pixels holding the upright points [points] (x, y pairs); a single line gets a box one pixel thick. */
    fun toImage(points: FloatArray): FrameRect = bounds(points, ::imageX, ::imageY)

    /**
     * Copies the image [source] (rows of [width] bytes) into [destination] upright, as
     * [uprightHeight] rows of [uprightWidth] bytes.
     */
    fun turnUpright(source: ByteArray, destination: ByteArray) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = when (quarterTurns) {
                    1 -> x * height + (height - 1 - y)
                    2 -> (height - 1 - y) * width + (width - 1 - x)
                    3 -> (width - 1 - x) * height + y
                    else -> row + x
                }
                destination[index] = source[row + x]
            }
        }
    }

    private inline fun bounds(
        points: FloatArray,
        mapX: (Float, Float) -> Float,
        mapY: (Float, Float) -> Float,
    ): FrameRect {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until points.size - 1 step 2) {
            val x = mapX(points[i], points[i + 1])
            val y = mapY(points[i], points[i + 1])
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        val left = floor(minX).toInt()
        val top = floor(minY).toInt()
        return FrameRect(left, top, maxOf(ceil(maxX).toInt(), left + 1), maxOf(ceil(maxY).toInt(), top + 1))
    }
}
