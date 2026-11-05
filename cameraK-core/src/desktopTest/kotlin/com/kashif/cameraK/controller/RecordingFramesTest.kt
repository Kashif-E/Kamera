@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The recorder reads the producer's leased frames, not a copy of each (§8 desktop, D4'; closes
 * desktop-recording-pipeline's per-frame copy): a recording allocates no image per frame, so the
 * images it is handed are the producer's few slots, each given back once written.
 */
class RecordingFramesTest {
    @Test
    fun recorderUsesLeasedFramesWithoutCopies() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val handed = Collections.newSetFromMap(IdentityHashMap<BufferedImage, Boolean>())
        var written = 0
        rig.recordingConverter = {
            object : Java2DFrameConverter() {
                override fun convert(img: BufferedImage?): Frame? {
                    img?.let { handed += it }
                    written++
                    return super.convert(img)
                }
            }
        }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut)
            repeat(FRAMES) { steps.frameAt(1_040L + it * 40) }
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))

            assertEquals(FRAMES, rig.recorders.single().recordCount.get(), "frames recorded")
            assertEquals(FRAMES, written, "frames the recorder's converter was handed")
            // The producer holds the two latest frames for the preview and converts into a third
            // slot; a copy per frame would be ten images.
            assertTrue(handed.size <= 3, "images allocated for $FRAMES recorded frames: ${handed.size}")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    private companion object {
        const val FRAMES = 10
    }
}
