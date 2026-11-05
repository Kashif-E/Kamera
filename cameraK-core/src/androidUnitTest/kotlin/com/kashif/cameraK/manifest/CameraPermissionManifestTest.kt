package com.kashif.cameraK.manifest

import android.Manifest
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The library asks for CAMERA at runtime. Android denies a permission the merged manifest does not
 * declare without showing a dialog, so the library declares it. CAMERA alone makes Google Play
 * assume the app needs a back camera with autofocus and hide it from devices without one
 * (Chromebooks, many tablets), so the camera features are declared optional; an app that does need
 * one declares it required, and the manifest merger keeps the stricter value.
 */
class CameraPermissionManifestTest {
    private val manifest = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/androidMain/AndroidManifest.xml"))

    @Test
    fun libraryManifest_declaresTheCameraPermissionItRequests() {
        val permissions = manifest.elements("uses-permission").map { it.getAttributeNS(ANDROID_NS, "name") }

        assertTrue(Manifest.permission.CAMERA in permissions, "declared permissions: $permissions")
    }

    @Test
    fun libraryManifest_declaresEveryCameraFeatureOptional() {
        val features = manifest.elements("uses-feature")
            .associate { it.getAttributeNS(ANDROID_NS, "name") to it.getAttributeNS(ANDROID_NS, "required") }

        assertEquals(
            mapOf(
                "android.hardware.camera.any" to "false",
                "android.hardware.camera" to "false",
                "android.hardware.camera.autofocus" to "false",
                "android.hardware.camera.flash" to "false",
            ),
            features,
        )
    }

    /** Core writes app-owned files only; the media saver declares the storage permission it needs. */
    @Test
    fun libraryManifestDeclaresNoStoragePermission() {
        val permissions = manifest.elements("uses-permission").map { it.getAttributeNS(ANDROID_NS, "name") }

        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions, "declared permissions: $permissions")
    }

    private fun org.w3c.dom.Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
