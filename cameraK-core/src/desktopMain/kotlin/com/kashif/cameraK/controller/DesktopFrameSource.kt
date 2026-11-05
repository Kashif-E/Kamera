package com.kashif.cameraK.controller

import com.kashif.cameraK.frames.FramePlane

/** A plane over part of a pooled buffer (D4' red stub). */
internal class DesktopFramePlane(
    private val bytes: ByteArray,
    private val offset: Int,
    override val rowStride: Int,
    override val pixelStride: Int,
    override val size: Int,
) : FramePlane {
    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int = TODO("D4'")

    override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int): Unit =
        TODO("D4'")
}
