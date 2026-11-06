package com.kashif.imagesaverplugin

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The saver's 1.x surface, which IS' kept at WARNING level, is gone in 2.0 (design §10.4):
 * ImageSaverLegacy.kt's types and config-taking remember, and the byte and path members. Only
 * `getByteArrayFrom` stays, as an ERROR-level hint (SaverDeprecationHintTest).
 */
class LegacySaverSurfaceGoneTest {
    @Test
    fun removedSymbolsAreAbsent() {
        val present = mutableListOf<String>()
        listOf(
            "com.kashif.imagesaverplugin.ImageSaverConfig",
            "com.kashif.imagesaverplugin.ImageSaveResult",
            "com.kashif.imagesaverplugin.ImageSaverLegacyKt",
        ).filter(::declared).forEach { present += "class $it" }

        Class.forName("com.kashif.imagesaverplugin.ImageSaverPluginKt").methods
            .filter { method -> method.name == "rememberImageSaverPlugin" && method.parameterTypes.none(::isConfig) }
            .forEach {
                present +=
                    "rememberImageSaverPlugin(${it.parameterTypes.joinToString { type -> type.simpleName }})"
            }

        ImageSaverPlugin::class.java.methods.forEach { method ->
            val parameters = method.parameterTypes.toList()
            when {
                method.name == "saveImage" -> present += "ImageSaverPlugin.saveImage"
                method.name == "save" && parameters.firstOrNull() == ByteArray::class.java ->
                    present += "ImageSaverPlugin.save(ByteArray, String?)"
                method.name == "readBytes" && parameters.firstOrNull() == String::class.java ->
                    present += "ImageSaverPlugin.readBytes(String)"
            }
        }

        assertTrue(present.isEmpty(), "1.x saver symbols still present:\n${present.joinToString("\n")}")
    }

    // The 2.0 remember takes a MediaSaverConfig; any other is the 1.x one.
    private fun isConfig(type: Class<*>): Boolean = type == MediaSaverConfig::class.java

    private fun declared(name: String): Boolean = try {
        Class.forName(name, false, javaClass.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
