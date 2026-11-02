package com.kashif.analyzerPlugin

import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber

/** A camera that is not there: starting it fails, so a test never opens a real device. */
internal class AbsentCamera : FrameGrabber() {
    override fun start(): Unit = throw Exception("no camera in unit tests")

    override fun stop() = Unit

    override fun trigger() = Unit

    override fun grab(): Frame? = null

    override fun release() = Unit
}
