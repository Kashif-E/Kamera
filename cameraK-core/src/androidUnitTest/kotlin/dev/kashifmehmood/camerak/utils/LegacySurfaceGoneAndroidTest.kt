package dev.kashifmehmood.camerak.utils

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android-only 1.x declarations 2.0 removes (design §10.4, §14 rows 7 and 31): the deprecated
 * no-op `MemoryManager` (Decision 23), the facade's `LegacyImageProxy`, which only the deleted
 * `registerImageAnalyzer` used, and the dead helpers of `utils/Extensions.kt`. Core's desktop tests
 * cannot see androidMain, so this checks them.
 */
class LegacySurfaceGoneAndroidTest {
    @Test
    fun removedSymbolsAreAbsent() {
        val present = listOf(
            "dev.kashifmehmood.camerak.utils.MemoryManager",
            "dev.kashifmehmood.camerak.controller.LegacyImageProxy",
            "dev.kashifmehmood.camerak.controller.CameraController",
        ).filter { name ->
            try {
                Class.forName(name, false, javaClass.classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

        // Extensions.kt's dead public helpers (design §2.1, §10.4): no main source of any module calls them.
        val extensions = try {
            Class.forName("dev.kashifmehmood.camerak.utils.ExtensionsKt", false, javaClass.classLoader).methods.map {
                it.name
            }
        } catch (_: ClassNotFoundException) {
            emptyList()
        }
        val helpers = listOf("getActivityOrNull", "compressToByteArray", "toByteArray")
        val presentHelpers = helpers.filter { it in extensions }.map { "dev.kashifmehmood.camerak.utils.$it" }

        assertTrue((present + presentHelpers).isEmpty(), "1.x symbols still present: ${present + presentHelpers}")
    }
}
