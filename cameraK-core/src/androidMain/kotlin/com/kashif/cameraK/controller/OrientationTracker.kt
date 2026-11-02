package com.kashif.cameraK.controller

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import com.kashif.cameraK.enums.DeviceOrientation

/**
 * The device orientation the accelerometer reports, and the app's explicit target orientation.
 * Only the device-orientation output follows the sensor; the capture rotation and the ViewPort follow
 * display changes ([UseCaseBinder]).
 */
internal class OrientationTracker(private val context: Context) {
    @Volatile
    var deviceOrientation = DeviceOrientation.PORTRAIT
        private set

    /** The capture orientation the app asked for, overriding the display rotation; null follows the display. */
    @Volatile
    var targetOrientation: DeviceOrientation? = null

    @Volatile
    private var callback: ((DeviceOrientation) -> Unit)? = null
    private var listener: OrientationEventListener? = null

    /** Reports orientation changes to [callback] while it is set; null stops the sensor. */
    fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        this.callback = callback
        if (callback != null) {
            if (listener == null) {
                listener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(angle: Int) {
                        val newOrientation = orientationForSensorAngle(angle) ?: return
                        if (newOrientation != deviceOrientation) {
                            deviceOrientation = newOrientation
                            this@OrientationTracker.callback?.invoke(newOrientation)
                        }
                    }
                }
            }
            listener?.enable()
        } else {
            listener?.disable()
            listener = null
        }
    }

    /** Stops the sensor and drops the callback. */
    fun release() {
        listener?.disable()
        listener = null
        callback = null
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
