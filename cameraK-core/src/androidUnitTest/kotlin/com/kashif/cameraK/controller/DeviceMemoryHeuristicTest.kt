@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.app.ActivityManager
import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/**
 * Android keeps cached processes in memory until it needs the space, so most of the device's RAM is
 * in use on a healthy phone. A device-wide used/total ratio says nothing about this app's pressure,
 * and capture behaviour must not change because of it.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceMemoryHeuristicTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deviceRamIsMostlyInUse() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = 8L shl 30
                availMem = 1L shl 30
                lowMemory = false
            },
        )
    }

    @Test
    fun qualityPrioritizationNone_capturesAtMaximumQualityWhateverTheDeviceWideMemoryUse() = runTest {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(
            testScheduler,
            CameraConfiguration { qualityPrioritization = QualityPrioritization.NONE },
        )
        open(platform, engine)

        val imageCapture = platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
        assertEquals(
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
            imageCapture.captureMode,
            "the capture mode followed device-wide memory use",
        )
        engine.closeAndJoin()
    }
}
