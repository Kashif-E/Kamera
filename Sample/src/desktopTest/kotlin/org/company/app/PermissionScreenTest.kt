@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import com.kashif.cameraK.permissions.PermissionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The permission screen offers Open Settings only when the system will no longer ask: a permanently
 * denied camera can be granted only there, and for any other status the system prompt still can.
 */
class PermissionScreenTest {
    @Test
    fun openSettingsShowsOnlyForAPermanentlyDeniedCamera() = runTest {
        val statuses = listOf(
            null,
            PermissionStatus.NotDetermined,
            PermissionStatus.Denied(shouldShowRationale = true),
            PermissionStatus.Denied(shouldShowRationale = false),
            PermissionStatus.Restricted,
            PermissionStatus.PermanentlyDenied,
        )
        val shown = statuses.associateWith { status ->
            SampleScene(this) { PermissionScreen(cameraStatus = status, onAllow = {}, onOpenSettings = {}) }
                .use { it.shows("Open Settings") }
        }
        assertEquals(statuses.associateWith { it == PermissionStatus.PermanentlyDenied }, shown)
    }

    @Test
    fun noOpenSettingsWhereThePlatformHasNone() = runTest {
        SampleScene(this) {
            PermissionScreen(cameraStatus = PermissionStatus.PermanentlyDenied, onAllow = {}, onOpenSettings = null)
        }.use { assertFalse(it.shows("Open Settings")) }
    }

    @Test
    fun allowAsksAgain() = runTest {
        var asked = 0
        SampleScene(this) {
            PermissionScreen(cameraStatus = PermissionStatus.NotDetermined, onAllow = { asked++ }, onOpenSettings = {})
        }.use { scene ->
            assertTrue(scene.shows("Camera access is needed"))
            scene.tap(scene.node("Allow camera access"))
        }
        assertEquals(1, asked)
    }
}
