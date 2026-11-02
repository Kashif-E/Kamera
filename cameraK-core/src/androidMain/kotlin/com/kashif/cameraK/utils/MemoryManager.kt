package com.kashif.cameraK.utils

import android.content.Context

private const val REMOVED =
    "CameraK no longer reads device-wide memory use: it changed nothing useful and called System.gc(). " +
        "Nothing replaces it; delete the call."

/**
 * Formerly judged memory pressure from device-wide RAM use, which is normally high on Android because
 * the system keeps cached processes, and then called `System.gc()` and changed the capture mode. The
 * library no longer uses it; every member does nothing, and it will be removed in a later release.
 */
@Deprecated(REMOVED)
object MemoryManager {
    @Deprecated(REMOVED)
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) = Unit

    @Deprecated(REMOVED)
    fun updateMemoryStatus() = Unit

    @Deprecated(REMOVED)
    fun clearBufferPools() = Unit

    /** Always false: device-wide memory use is not memory pressure. */
    @Deprecated(REMOVED)
    fun isUnderMemoryPressure(): Boolean = false
}
