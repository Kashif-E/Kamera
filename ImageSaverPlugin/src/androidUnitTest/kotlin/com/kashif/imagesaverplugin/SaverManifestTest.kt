package com.kashif.imagesaverplugin

import android.Manifest
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/**
 * The saver writes a file under the public directory itself below API 29, which needs
 * WRITE_EXTERNAL_STORAGE, and only there. It declares it, capped at 28; core no longer does. Moved
 * from core's GalleryCaptureTest.
 */
class SaverManifestTest {
    @Test
    fun declaresWriteExternalStorageOnlyUpToApi28() {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/androidMain/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).map { permissions.item(it) as Element }
            .associate { it.getAttributeNS(ANDROID_NS, "name") to it.getAttributeNS(ANDROID_NS, "maxSdkVersion") }

        assertEquals(mapOf(Manifest.permission.WRITE_EXTERNAL_STORAGE to "28"), declared)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
