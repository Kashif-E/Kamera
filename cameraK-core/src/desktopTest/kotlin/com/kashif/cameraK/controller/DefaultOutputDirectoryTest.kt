@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * With no output directory configured, a still lands in the app-owned `Documents/CameraK` under the
 * user's home, and nothing is written under the home's `Pictures` (Decision 10, design §6.1). A
 * configuration can no longer name a gallery folder (CF'), so this is the only default there is;
 * publishing is the media saver's.
 */
class DefaultOutputDirectoryTest {
    @Test
    fun defaultIsTheDocumentsLocation() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        // A 4:3 configuration for the 4:3 camera, so the blit crops nothing (D4').
        val sut = rig.engine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
        try {
            DesktopRecordingSteps(this, rig).open(sut)
            val pending = async { sut.capture() }
            rig.settle()
            rig.camera.deliverFrame()
            rig.settle()
            val image = assertIs<CameraKResult.Success<CapturedImage>>(pending.await(), "the capture").value

            assertEquals(File(rig.home, "Documents/CameraK").canonicalFile, File(image.path).canonicalFile.parentFile)
            assertEquals(ImageFormat.JPEG, image.format)
            assertEquals(64 to 48, image.width to image.height)
            assertNotNull(ImageIO.read(File(image.path)), "the capture is not a readable image")
            assertFalse(File(rig.home, "Pictures").exists(), "the engine wrote to the gallery folder")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }
}
