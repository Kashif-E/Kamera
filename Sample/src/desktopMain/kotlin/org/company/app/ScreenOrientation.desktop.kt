package org.company.app

import androidx.compose.runtime.Composable
import dev.kashifmehmood.camerak.enums.DeviceOrientation

// A desktop window is not turned, and the desktop engine reports PORTRAIT.
@Composable
internal actual fun rememberScreenOrientation(): DeviceOrientation = DeviceOrientation.PORTRAIT
