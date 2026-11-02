package com.kashif.cameraK.coroutines

internal actual fun Throwable.isFatal(): Boolean = this is OutOfMemoryError
