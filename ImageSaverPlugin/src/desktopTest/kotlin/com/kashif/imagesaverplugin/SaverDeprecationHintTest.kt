package com.kashif.imagesaverplugin

import com.kashif.cameraK.permissions.PermissionStatus
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The saver's one 1.x hint, `getByteArrayFrom` (Decision 24): ERROR level with a ReplaceWith, and a
 * call that reaches it anyway, from code compiled against 1.x, throws UnsupportedOperationException
 * instead of reading.
 */
class SaverDeprecationHintTest {
    @Test
    fun everyHintIsErrorLevelWithReplaceWithAndThrows() {
        val home = Files.createTempDirectory("camerak-saver-hint").toFile()
        try {
            val config = MediaSaverConfig { }
            val saver = ImageSaverPlugin(config, DesktopGalleryStore(config, home), { PermissionStatus.Granted })
            val hint = assertNotNull(
                ImageSaverPlugin::class.java.methods.singleOrNull { it.name == "getByteArrayFrom" },
                "getByteArrayFrom is the 1.x read's hint",
            )

            val deprecation =
                assertNotNull(hint.getAnnotation(Deprecated::class.java), "getByteArrayFrom is deprecated")
            assertEquals(DeprecationLevel.ERROR, deprecation.level)
            assertTrue(deprecation.replaceWith.expression.isNotBlank(), "getByteArrayFrom has no ReplaceWith")

            val thrown = try {
                hint.invoke(saver, home.resolve("missing.jpg").path)
                fail("getByteArrayFrom returned instead of throwing")
            } catch (e: InvocationTargetException) {
                e.cause
            }
            assertIs<UnsupportedOperationException>(thrown, "getByteArrayFrom threw $thrown")
        } finally {
            home.deleteRecursively()
        }
    }
}
