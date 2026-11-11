package org.company.app

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.kashif.cameraK.enums.DeviceOrientation

// The display's rotation, as the library pairs a target orientation with a Surface rotation.
@Composable
internal actual fun rememberScreenOrientation(): DeviceOrientation {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    return remember(view, configuration) {
        when (view.display?.rotation) {
            Surface.ROTATION_90 -> DeviceOrientation.LANDSCAPE_LEFT
            Surface.ROTATION_180 -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
            Surface.ROTATION_270 -> DeviceOrientation.LANDSCAPE_RIGHT
            else -> DeviceOrientation.PORTRAIT
        }
    }
}
