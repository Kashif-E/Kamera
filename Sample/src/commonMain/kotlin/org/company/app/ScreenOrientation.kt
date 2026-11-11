package org.company.app

import androidx.compose.runtime.Composable
import com.kashif.cameraK.enums.DeviceOrientation

/**
 * The way the screen is turned from the device's natural position, as the device orientation it
 * matches: an app that turns with the device is laid out in the orientation the device is held in.
 */
@Composable
internal expect fun rememberScreenOrientation(): DeviceOrientation

/**
 * The rotation that keeps the capture controls' icons upright on a [screen] turned as it is while the
 * device is held in [device].
 */
internal fun uprightRotation(device: DeviceOrientation, screen: DeviceOrientation): Float {
    val turn = ((device.compensationDegrees - screen.compensationDegrees) % FULL_TURN + FULL_TURN) % FULL_TURN
    return if (turn > HALF_TURN) turn - FULL_TURN else turn
}

private const val FULL_TURN = 360f
private const val HALF_TURN = 180f
