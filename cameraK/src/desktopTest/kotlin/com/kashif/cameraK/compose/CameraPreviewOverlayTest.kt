package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import com.kashif.cameraK.enums.DeviceOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every platform's CameraPreview draws its overlay through CameraPreviewOverlay. The overlay's
 * receiver stays the same object while the box and orientation do, so when the preview recomposes
 * for an unrelated reason the overlay skips instead of running again; a new orientation gives it a
 * new receiver, so it does run then.
 */
class CameraPreviewOverlayTest {
    private class Rig {
        var tick by mutableStateOf(0)
        var orientation by mutableStateOf(DeviceOrientation.PORTRAIT)
        val seen = mutableListOf<DeviceOrientation>()
        private val overlay: @Composable CameraPreviewScope.() -> Unit = { seen += deviceOrientation }
        private val scene = ImageComposeScene(width = 10, height = 10) {
            Box {
                tick // recomposes the preview for a reason the overlay does not care about
                CameraPreviewOverlay(orientation, overlay)
            }
        }

        fun frame() {
            Snapshot.sendApplyNotifications()
            scene.render()
        }

        fun close() = scene.close()
    }

    @Test
    fun theOverlaySkipsWhenThePreviewRecomposesForAnUnrelatedChange() {
        val rig = Rig()
        try {
            rig.frame()
            repeat(3) {
                rig.tick++
                rig.frame()
            }
            assertEquals(listOf(DeviceOrientation.PORTRAIT), rig.seen, "the overlay ran again for every recomposition")
        } finally {
            rig.close()
        }
    }

    /** Guard: a new orientation reaches the overlay (green before the fix). */
    @Test
    fun theOverlayRunsAgainWithANewOrientation() {
        val rig = Rig()
        try {
            rig.frame()
            rig.orientation = DeviceOrientation.LANDSCAPE_LEFT
            rig.frame()
            assertEquals(
                DeviceOrientation.LANDSCAPE_LEFT,
                rig.seen.last(),
                "the overlay did not see the new orientation",
            )
        } finally {
            rig.close()
        }
    }
}
