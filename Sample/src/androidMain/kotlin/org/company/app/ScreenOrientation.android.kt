package org.company.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.kashif.cameraK.enums.DeviceOrientation

// The display's rotation, as the library pairs a target orientation with a Surface rotation. A half
// turn, from one landscape to the other, recreates no activity, and not every Android version reports
// it as a configuration change (CameraX reads the rotation from a DisplayListener for this), so the
// rotation is read again whenever the display changes.
@Composable
internal actual fun rememberScreenOrientation(): DeviceOrientation {
    val view = LocalView.current
    var orientation by remember(view) { mutableStateOf(view.screenOrientation()) }
    DisposableEffect(view) {
        val displays = view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId) orientation = view.screenOrientation()
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displays.registerDisplayListener(listener, view.handler)
        orientation = view.screenOrientation()
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    return orientation
}

private fun View.screenOrientation(): DeviceOrientation = when (display?.rotation) {
    Surface.ROTATION_90 -> DeviceOrientation.LANDSCAPE_LEFT
    Surface.ROTATION_180 -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
    Surface.ROTATION_270 -> DeviceOrientation.LANDSCAPE_RIGHT
    else -> DeviceOrientation.PORTRAIT
}
