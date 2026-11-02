package com.kashif.cameraK.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** The dispatchers CameraK runs on. Tests pass test dispatchers; apps keep the defaults. */
public class CameraKDispatchers(
    public val main: CoroutineDispatcher = Dispatchers.Main,
    public val default: CoroutineDispatcher = Dispatchers.Default,
    public val io: CoroutineDispatcher = Dispatchers.IO,
)
