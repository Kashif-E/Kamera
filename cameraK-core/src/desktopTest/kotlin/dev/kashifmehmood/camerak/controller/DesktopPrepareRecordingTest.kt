@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.StorageFailure
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Desktop has no output to add ahead of time: its recorder opens at start. `prepareRecording`
 * validates the configuration the start would refuse, and otherwise returns `Success` with no
 * recorder opened.
 */
class DesktopPrepareRecordingTest {
    @Test
    fun prepareValidatesTheConfigurationAndOpensNothing() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val notAFolder = File(rig.home, "not-a-folder").apply {
            parentFile.mkdirs()
            writeText("a file")
        }
        try {
            steps.open(sut)
            val events = steps.events(sut)

            val unwritable = sut.prepareRecording(steps.video(outputDirectory = File(notAFolder, "videos").path))
            val storage = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(unwritable).error)
            assertEquals(StorageFailure.NOT_WRITABLE, storage.kind)

            assertEquals(CameraKResult.Success(Unit), sut.prepareRecording(steps.video()))
            rig.settle()
            assertEquals(emptyList(), rig.recorders.toList(), "prepareRecording opened a recorder")
            assertEquals(emptyList(), events.toList(), "recording events of a prepare")
        } finally {
            sut.close()
            rig.settle()
        }
    }
}
