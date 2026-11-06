package com.kashif.cameraK.utils

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android-only 1.x declarations 2.0 removes (design §10.4, §14 rows 7 and 31): the deprecated
 * no-op `MemoryManager` (Decision 23) and the facade's `LegacyImageProxy`, which only the deleted
 * `registerImageAnalyzer` used. Core's desktop tests cannot see androidMain, so this checks them.
 */
class LegacySurfaceGoneAndroidTest {
    @Test
    fun removedSymbolsAreAbsent() {
        val present = listOf(
            "com.kashif.cameraK.utils.MemoryManager",
            "com.kashif.cameraK.controller.LegacyImageProxy",
            "com.kashif.cameraK.controller.CameraController",
        ).filter { name ->
            try {
                Class.forName(name, false, javaClass.classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

        assertTrue(present.isEmpty(), "1.x symbols still present: $present")
    }
}
