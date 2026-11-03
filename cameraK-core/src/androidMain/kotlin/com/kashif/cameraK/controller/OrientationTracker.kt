package com.kashif.cameraK.controller

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import com.kashif.cameraK.enums.DeviceOrientation

/**
 * The device orientation the accelerometer reports, and the app's explicit target orientation.
 * Only the device-orientation output follows the sensor; the capture rotation and the ViewPort follow
 * display changes ([UseCaseBinder]). The sensor runs only between [start] and [stop]: a reading that
 * arrives after [stop] changes nothing. Main thread.
 */
internal class OrientationTracker(private val context: Context) {
    @Volatile
    var deviceOrientation = DeviceOrientation.PORTRAIT
        private set

    /** The capture orientation the app asked for, overriding the display rotation; null follows the display. */
    @Volatile
    var targetOrientation: DeviceOrientation? = null

    // Set between start and stop; a reading the sensor delivers outside that window is dropped.
    @Volatile
    private var onChange: ((DeviceOrientation) -> Unit)? = null
    private var listener: OrientationEventListener? = null

    /** Starts the sensor, reporting each change of bucket to [onChange] until [stop]. */
    fun start(onChange: (DeviceOrientation) -> Unit) {
        this.onChange = onChange
        val sensor = listener ?: object : OrientationEventListener(context) {
            override fun onOrientationChanged(angle: Int) {
                val report = this@OrientationTracker.onChange ?: return
                val newOrientation = orientationForSensorAngle(angle) ?: return
                if (newOrientation != deviceOrientation) {
                    deviceOrientation = newOrientation
                    report(newOrientation)
                }
            }
        }.also { listener = it }
        sensor.enable()
    }

    /** Stops the sensor; nothing is reported until the next [start]. */
    fun stop() {
        onChange = null
        listener?.disable()
    }

    /** Stops the sensor for good. */
    fun release() {
        stop()
        listener = null
    }
}

/** The orientation bucket an [OrientationEventListener] angle falls in, or null for none. */
internal fun orientationForSensorAngle(angle: Int): DeviceOrientation? = when {
    angle == OrientationEventListener.ORIENTATION_UNKNOWN -> null
    angle in 315..359 || angle in 0..44 -> DeviceOrientation.PORTRAIT
    angle in 45..134 -> DeviceOrientation.LANDSCAPE_RIGHT
    angle in 135..224 -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
    angle in 225..314 -> DeviceOrientation.LANDSCAPE_LEFT
    else -> null
}

/** The [Surface] rotation a capture takes for [this] target orientation. */
internal fun DeviceOrientation.toSurfaceRotation(): Int = when (this) {
    DeviceOrientation.PORTRAIT -> Surface.ROTATION_0
    DeviceOrientation.LANDSCAPE_LEFT -> Surface.ROTATION_90
    DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> Surface.ROTATION_180
    DeviceOrientation.LANDSCAPE_RIGHT -> Surface.ROTATION_270
}
