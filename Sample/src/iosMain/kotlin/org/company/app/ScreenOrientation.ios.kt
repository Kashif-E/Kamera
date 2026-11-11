package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import com.kashif.cameraK.enums.DeviceOrientation
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIWindowScene

// The interface orientation. An interface turned landscape right is the device's landscape left,
// which the library maps from UIDeviceOrientation. A half turn, from one landscape to the other,
// leaves the window's size as it was, so the orientation is read again when the size changes, when
// the device turns, and on the main queue's next turn after that, once the interface has followed.
@Composable
internal actual fun rememberScreenOrientation(): DeviceOrientation {
    val size = LocalWindowInfo.current.containerSize
    var orientation by remember { mutableStateOf(interfaceOrientation()) }
    DisposableEffect(size) {
        orientation = interfaceOrientation()
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        val observer = center.addObserverForName(UIDeviceOrientationDidChangeNotification, null, main) { _ ->
            orientation = interfaceOrientation()
            main.addOperationWithBlock { orientation = interfaceOrientation() }
        }
        onDispose { center.removeObserver(observer) }
    }
    return orientation
}

private fun interfaceOrientation(): DeviceOrientation {
    val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
    return when (scene?.interfaceOrientation) {
        UIInterfaceOrientationLandscapeRight -> DeviceOrientation.LANDSCAPE_LEFT
        UIInterfaceOrientationLandscapeLeft -> DeviceOrientation.LANDSCAPE_RIGHT
        UIInterfaceOrientationPortraitUpsideDown -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
        else -> DeviceOrientation.PORTRAIT
    }
}
