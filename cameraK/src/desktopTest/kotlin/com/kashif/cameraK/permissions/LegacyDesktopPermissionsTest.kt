@file:Suppress("DEPRECATION")

package com.kashif.cameraK.permissions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1.x desktop permission API grants from an effect, once, not from the composable's body on
 * every recomposition (CC-23).
 */
class LegacyDesktopPermissionsTest {
    @Test
    fun requestCameraPermissionGrantsOnceAcrossRecompositions() {
        var tick by mutableIntStateOf(0)
        val grantedAt = mutableListOf<Int>()
        val scene = ImageComposeScene(width = 10, height = 10) {
            val permissions = providePermissions()
            val seen = tick
            permissions.RequestCameraPermission(onGranted = { grantedAt += seen }, onDenied = {})
        }
        try {
            scene.render()
            repeat(3) {
                tick++
                Snapshot.sendApplyNotifications()
                scene.render()
            }
        } finally {
            scene.close()
        }

        assertEquals(listOf(0), grantedAt, "onGranted ran on recompositions, at these ticks")
    }
}
