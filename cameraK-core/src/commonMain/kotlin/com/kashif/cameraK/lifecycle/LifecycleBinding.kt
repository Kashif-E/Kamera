package com.kashif.cameraK.lifecycle

import androidx.lifecycle.Lifecycle
import com.kashif.cameraK.state.CameraKStateHolder

/** Drives the holder from [lifecycle]; closing the result stops following it and stops the holder. */
public fun CameraKStateHolder.bindToLifecycle(lifecycle: Lifecycle): AutoCloseable = TODO("LC'")
