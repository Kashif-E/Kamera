package com.kashif.cameraK.coroutines

/**
 * Whether this throwable means the process can no longer run correctly, so no catch may absorb it:
 * a `VirtualMachineError` (which covers `OutOfMemoryError`) on the JVM and Android, an
 * `OutOfMemoryError` on Kotlin/Native. A `LinkageError` is not fatal: it means a native library is
 * missing, which CameraK reports as an error.
 */
internal expect fun Throwable.isFatal(): Boolean
