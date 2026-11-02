package com.kashif.cameraK.error

import kotlin.jvm.JvmInline

/**
 * Open: new values can be added in a minor release, so a `when` over it needs an `else` branch.
 * A command or lifecycle step, named in [CameraKError.Timeout], [CameraKError.Busy] and [CameraKError.InvalidState].
 */
@JvmInline
public value class CameraOperation private constructor(public val name: String) {
    override fun toString(): String = TODO("S1' stub")

    public companion object {
        public val START: CameraOperation = CameraOperation("START")
        public val CAPTURE: CameraOperation = CameraOperation("CAPTURE")
        public val RECORD_PREPARE: CameraOperation = CameraOperation("RECORD_PREPARE")
        public val RECORD_START: CameraOperation = CameraOperation("RECORD_START")
        public val RECORD_STOP: CameraOperation = CameraOperation("RECORD_STOP")
        public val RECORD_PAUSE: CameraOperation = CameraOperation("RECORD_PAUSE")
        public val RECORD_RESUME: CameraOperation = CameraOperation("RECORD_RESUME")
        public val ZOOM: CameraOperation = CameraOperation("ZOOM")
        public val FLASH: CameraOperation = CameraOperation("FLASH")
        public val TORCH: CameraOperation = CameraOperation("TORCH")
        public val LENS: CameraOperation = CameraOperation("LENS")

        /** A camera switch that changes only the device type (wide, ultra-wide, telephoto). */
        public val DEVICE_TYPE: CameraOperation = CameraOperation("DEVICE_TYPE")
        public val ORIENTATION: CameraOperation = CameraOperation("ORIENTATION")
    }
}
