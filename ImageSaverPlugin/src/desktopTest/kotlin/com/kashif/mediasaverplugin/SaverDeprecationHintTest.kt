package com.kashif.mediasaverplugin

import androidx.compose.ui.ImageComposeScene
import com.kashif.cameraK.permissions.PermissionStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The saver's 1.x hints, one rule per kind (design §10.4, §12.4 C1'):
 * - `getByteArrayFrom` (Decision 24): ERROR level with a ReplaceWith, and a call that reaches it
 *   anyway, from code compiled against 1.x, throws UnsupportedOperationException instead of reading.
 * - The classes C1'b moves from `com.kashif.imagesaverplugin` to `com.kashif.mediasaverplugin`
 *   (§9.4): each old name is a typealias, ERROR level with a ReplaceWith naming the 2.0 class, read
 *   from the `<Alias>$annotations` method the compiler writes on the file facade, and it expands to
 *   that class.
 * - `rememberImageSaverPlugin`, renamed `rememberMediaSaverPlugin` with the class: ERROR level with a
 *   ReplaceWith, and composing it throws. A reflective call cannot show the throw, because the
 *   Compose compiler adds a Composer parameter, so it is composed.
 */
class SaverDeprecationHintTest {
    @Test
    fun everyHintIsErrorLevelWithReplaceWithAndThrows() {
        val home = Files.createTempDirectory("camerak-saver-hint").toFile()
        try {
            val config = MediaSaverConfig { }
            val saver = MediaSaverPlugin(config, DesktopGalleryStore(config, home), { PermissionStatus.Granted })
            val hint = assertNotNull(
                MediaSaverPlugin::class.java.methods.singleOrNull { it.name == "getByteArrayFrom" },
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

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun everyMovedClassLeavesAnErrorLevelTypealiasAtItsOldName() {
        val facade = Class.forName(OLD_FACADE)
        val expansions: Map<String, KClass<*>> = mapOf(
            "ImageSaverPlugin" to com.kashif.imagesaverplugin.ImageSaverPlugin::class,
            "MediaSaverConfig" to com.kashif.imagesaverplugin.MediaSaverConfig::class,
            "GalleryDestination" to com.kashif.imagesaverplugin.GalleryDestination::class,
            "SavedImage" to com.kashif.imagesaverplugin.SavedImage::class,
            "SavedVideo" to com.kashif.imagesaverplugin.SavedVideo::class,
        )
        val wrong = mutableListOf<String>()
        MOVED.forEach { (old, new) ->
            val replacement = "$NEW_PACKAGE.$new"
            val alias = facade.declaredMethods.singleOrNull { it.name == "$old\$annotations" }
            val deprecation = alias?.getAnnotation(Deprecated::class.java)
            val target = try {
                Class.forName(replacement)
            } catch (_: ClassNotFoundException) {
                null
            }
            when {
                alias == null -> wrong += "$OLD_PACKAGE.$old is not a typealias in $OLD_FACADE"
                deprecation == null -> wrong += "the typealias $old is not deprecated"
                deprecation.level != DeprecationLevel.ERROR -> wrong += "the typealias $old is ${deprecation.level}"
                new !in deprecation.replaceWith.expression || replacement !in deprecation.replaceWith.imports ->
                    wrong += "the typealias $old's ReplaceWith ${deprecation.replaceWith} does not name $replacement"
            }
            when {
                target == null -> wrong += "$replacement does not exist"
                // Without kotlin-reflect each KClass is a fresh wrapper, so the classes they wrap are compared.
                expansions[old]?.java !== target -> wrong += "$OLD_PACKAGE.$old is ${expansions[old]}, not $replacement"
            }
        }

        assertTrue(wrong.isEmpty(), "moved classes without their hint:\n${wrong.joinToString("\n")}")
    }

    @Test
    fun theRenamedRememberIsAnErrorLevelHintThatThrows() = runTest {
        val hints = Class.forName(OLD_FACADE).methods.filter { it.name == "rememberImageSaverPlugin" }
            .mapNotNull { it.getAnnotation(Deprecated::class.java) }
        assertTrue(hints.isNotEmpty(), "rememberImageSaverPlugin is not deprecated")
        hints.forEach { hint ->
            assertEquals(DeprecationLevel.ERROR, hint.level)
            assertTrue("rememberMediaSaverPlugin" in hint.replaceWith.expression, "ReplaceWith is ${hint.replaceWith}")
        }

        @Suppress("DEPRECATION_ERROR")
        val thrown = compose { com.kashif.imagesaverplugin.rememberImageSaverPlugin(MediaSaverConfig { }) }

        assertIs<UnsupportedOperationException>(thrown, "composing rememberImageSaverPlugin threw $thrown")
    }

    // Composes [content] once; returns what composing threw.
    private fun TestScope.compose(content: @androidx.compose.runtime.Composable () -> Unit): Throwable? {
        var scene: ImageComposeScene? = null
        return try {
            scene =
                ImageComposeScene(width = 10, height = 10, coroutineContext = StandardTestDispatcher(testScheduler)) {
                    content()
                }
            scene.render()
            runCurrent()
            null
        } catch (e: Throwable) {
            generateSequence(e) { it.cause }.firstOrNull { it is UnsupportedOperationException } ?: e
        } finally {
            // A scene whose composition threw may throw again while closing; the first throw is the answer.
            runCatching { scene?.close() }.exceptionOrNull()?.let { println("Closing the scene threw: $it") }
        }
    }

    private companion object {
        const val OLD_PACKAGE = "com.kashif.imagesaverplugin"
        const val NEW_PACKAGE = "com.kashif.mediasaverplugin"
        const val OLD_FACADE = "$OLD_PACKAGE.ImageSaverPluginKt"

        val MOVED = listOf(
            "ImageSaverPlugin" to "MediaSaverPlugin",
            "MediaSaverConfig" to "MediaSaverConfig",
            "GalleryDestination" to "GalleryDestination",
            "SavedImage" to "SavedImage",
            "SavedVideo" to "SavedVideo",
        )
    }
}
