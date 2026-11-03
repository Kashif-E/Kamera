@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * [FakeCameraEngine]'s preview: Android's attachment does nothing on close, Apple's layer has no
 * session, and desktop's frames hold one fixed frame from construction on.
 */
internal expect fun fakePreviewSource(): PlatformPreviewSource
