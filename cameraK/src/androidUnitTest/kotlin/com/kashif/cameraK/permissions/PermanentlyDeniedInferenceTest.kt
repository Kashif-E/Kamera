package com.kashif.cameraK.permissions

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Android says a permission is permanently denied only indirectly: a denied request after which the
 * activity no longer shows a rationale. A first request the user only dismissed looks the same, so
 * the inference needs an earlier request. The request's result callback records it; core reads it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(InternalCameraKApi::class)
class PermanentlyDeniedInferenceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity: Activity = controller.get()

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun rationale(shown: Boolean) {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, shown)
    }

    private fun cameraStatus() = platformPermissionStatus(CameraKPlatformContext(activity), CameraKPermission.CAMERA)

    @Test
    fun deniedWithoutRationaleAfterAskIsPermanentlyDenied() {
        rationale(shown = true)
        recordPermissionResults(activity, activity, mapOf(Manifest.permission.CAMERA to false))
        assertEquals(PermissionStatus.Denied(shouldShowRationale = true), cameraStatus(), "after the first denial")

        rationale(shown = false)
        recordPermissionResults(activity, activity, mapOf(Manifest.permission.CAMERA to false))

        assertEquals(PermissionStatus.PermanentlyDenied, cameraStatus())
    }

    @Test
    fun firstRequestDismissedIsDeniedNotPermanentlyDenied() {
        // Dismissing the dialog denies the request and leaves no rationale, as "don't ask again" does.
        rationale(shown = false)
        recordPermissionResults(activity, activity, mapOf(Manifest.permission.CAMERA to false))

        assertIs<PermissionStatus.Denied>(cameraStatus())
    }
}
