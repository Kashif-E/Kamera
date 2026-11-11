package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import com.kashif.cameraK.enums.DeviceOrientation
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIWindowScene

// The interface orientation, read again when a rotation resizes the window. An interface turned
// landscape right is the device's landscape left, which the library maps from UIDeviceOrientation.
@Composable
internal actual fun rememberScreenOrientation(): DeviceOrientation {
    val size = LocalWindowInfo.current.containerSize
    return remember(size) {
        val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull {
            it is UIWindowScene
        } as? UIWindowScene
        when (scene?.interfaceOrientation) {
            UIInterfaceOrientationLandscapeRight -> DeviceOrientation.LANDSCAPE_LEFT
            UIInterfaceOrientationLandscapeLeft -> DeviceOrientation.LANDSCAPE_RIGHT
            UIInterfaceOrientationPortraitUpsideDown -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
            else -> DeviceOrientation.PORTRAIT
        }
    }
}
