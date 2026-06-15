package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDeviceOrientationDidChangeNotification

@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    key(controller) {
        DisposableEffect(controller) {
            val notificationCenter = NSNotificationCenter.defaultCenter
            val observer = notificationCenter.addObserverForName(
                UIDeviceOrientationDidChangeNotification,
                null,
                null,
            ) { _ ->
                controller.getCameraPreviewLayer()?.connection?.videoOrientation =
                    controller.effectiveVideoOrientation()
            }

            onDispose {
                notificationCenter.removeObserver(observer)
            }
        }

        Box(modifier = modifier) {
            UIKitViewController(
                factory = { controller },
                modifier = Modifier.fillMaxSize(),
                update = {},
            )
            if (overlay != null) {
                val scope = CameraPreviewScopeImpl(this, deviceOrientation)
                scope.overlay()
            }
        }
    }
}
