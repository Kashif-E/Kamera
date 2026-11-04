@file:OptIn(InternalCameraKApi::class, CameraKEngineApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi

@Composable
fun CameraPreview(
    holder: CameraKStateHolder,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    overlay: @Composable CameraPreviewScope.() -> Unit = {},
) {
    TODO("UI'")
}

@Composable
internal expect fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
)
