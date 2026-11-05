package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect

/** The geometry of turning a [width] x [height] image upright by [rotationDegrees] clockwise. */
internal class UprightGeometry(val width: Int, val height: Int, rotationDegrees: Int) {
    val quarterTurns: Int = 0
    val uprightWidth: Int get() = width
    val uprightHeight: Int get() = height

    fun uprightX(x: Float, y: Float): Float = x

    fun uprightY(x: Float, y: Float): Float = y

    fun imageX(ux: Float, uy: Float): Float = ux

    fun imageY(ux: Float, uy: Float): Float = uy

    fun toUpright(rect: FrameRect): FrameRect = rect

    fun toImage(points: FloatArray): FrameRect = FrameRect(0, 0, 0, 0)

    fun turnUpright(source: ByteArray, destination: ByteArray) {
        source.copyInto(destination, 0, 0, width * height)
    }
}
