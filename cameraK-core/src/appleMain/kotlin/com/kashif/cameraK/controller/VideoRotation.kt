package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureVideoOrientation

/**
 * How a photo, movie or preview connection is turned (design §2.4): by the rotation coordinator's
 * angle from iOS 17, otherwise by a video orientation.
 */
internal sealed interface VideoRotation {
    /** A target orientation, or the device's or interface's below iOS 17: `videoOrientation`. */
    data class Orientation(val orientation: AVCaptureVideoOrientation) : VideoRotation

    /** The rotation coordinator's angle in degrees, iOS 17 and later only: `videoRotationAngle`. */
    data class Angle(val degrees: Double) : VideoRotation
}

/** Turns this connection by [rotation], where the connection supports it. */
internal fun AVCaptureConnection.turn(rotation: VideoRotation) {
    when (rotation) {
        is VideoRotation.Orientation ->
            if (isVideoOrientationSupported()) videoOrientation = rotation.orientation
        is VideoRotation.Angle ->
            if (isVideoRotationAngleSupported(rotation.degrees)) videoRotationAngle = rotation.degrees
    }
}
