package com.kashif.cameraK.controller

import android.Manifest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/**
 * Core no longer writes the gallery (Decision 10, A3'): its capture cases moved to CaptureOutputTest
 * and the media saver's GallerySaveTest (IS'). The storage permission the 1.x gallery path needed
 * below API 29 stays declared until IS' moves it to the saver.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryCaptureTest {
    /** The gallery path below API 29 needs the permission, and only there, so the library declares it capped at 28. */
    @Test
    fun libraryManifest_declaresWriteExternalStorageOnlyUpToApi28() {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/androidMain/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).map { permissions.item(it) as org.w3c.dom.Element }
            .associate { it.getAttributeNS(ANDROID_NS, "name") to it.getAttributeNS(ANDROID_NS, "maxSdkVersion") }

        assertEquals("28", declared[Manifest.permission.WRITE_EXTERNAL_STORAGE], "declared permissions: $declared")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
