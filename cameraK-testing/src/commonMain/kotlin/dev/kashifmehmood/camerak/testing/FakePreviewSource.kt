@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.testing

import dev.kashifmehmood.camerak.engine.PlatformPreviewSource
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/**
 * [FakeCameraEngine]'s preview: Android's attachment does nothing on close, Apple's layer has no
 * session, and desktop's frames hold one fixed frame from construction on.
 */
internal expect fun fakePreviewSource(): PlatformPreviewSource
