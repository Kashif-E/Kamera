@file:OptIn(InternalCameraKApi::class)

package dev.kashifmehmood.camerak.compose

import androidx.compose.runtime.Composable
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

/** The desktop camera, with no grabber factory: the holder opens the enumerated devices. */
@Composable
internal actual fun platformContext(): CameraKPlatformContext = CameraKPlatformContext()

@Composable
internal actual fun rememberHostRecreation(): () -> Boolean = NeverRecreated

private val NeverRecreated: () -> Boolean = { false }
