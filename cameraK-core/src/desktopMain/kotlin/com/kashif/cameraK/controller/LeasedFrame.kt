package com.kashif.cameraK.controller

import java.awt.image.BufferedImage

/** One slot of the producer's pool: a frame, and the leases on it (D4' red stub). */
internal class LeasedFrame internal constructor(val width: Int, val height: Int) {
    val image: BufferedImage get() = TODO("D4'")

    val captureNanos: Long get() = TODO("D4'")

    fun retain(): LeasedFrame = TODO("D4'")

    fun release(): Unit = TODO("D4'")
}
