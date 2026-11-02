package com.kashif.cameraK.manifest

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/**
 * Whatever the library's manifest declares is merged into every app that depends on it, and an
 * androidx.startup initializer runs in every process of that app at start-up. Nothing in the library
 * needs to run before the app asks for a camera, so it declares no provider and no initializer.
 */
class StartupProviderTest {
    private val manifest = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/androidMain/AndroidManifest.xml"))

    @Test
    fun libraryManifest_declaresNoContentProvider() {
        val providers = manifest.elements("provider").map { it.getAttributeNS(ANDROID_NS, "name") }

        assertEquals(emptyList(), providers, "providers merged into every consumer app")
    }

    @Test
    fun libraryManifest_registersNoStartupInitializer() {
        val initializers = manifest.elements("meta-data")
            .filter { it.getAttributeNS(ANDROID_NS, "value") == "androidx.startup" }
            .map { it.getAttributeNS(ANDROID_NS, "name") }

        assertEquals(emptyList(), initializers, "initializers run at start-up in every consumer process")
    }

    private fun org.w3c.dom.Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
