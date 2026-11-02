package com.kashif.cameraK.permissions

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 1.x permission API asks for WRITE_EXTERNAL_STORAGE only where a gallery capture needs it: below
 * API 29, where MediaStore writes still need it. These cases moved here from camerak-core's
 * GalleryCaptureTest, because storagePermissionGranted is internal to this module.
 */
@RunWith(RobolectricTestRunner::class)
class StoragePermissionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [29])
    fun storagePermission_isNotNeededFromApi29() {
        assertTrue(storagePermissionGranted(context), "API 29 writes through MediaStore without WRITE_EXTERNAL_STORAGE")
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun storagePermission_isNeededBelowApi29() {
        assertFalse(storagePermissionGranted(context))

        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(storagePermissionGranted(context))
    }
}
