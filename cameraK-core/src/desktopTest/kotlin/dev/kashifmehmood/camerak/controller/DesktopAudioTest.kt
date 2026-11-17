@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.error.CameraFeature
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.sound.sampled.LineUnavailableException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A desktop recording with audio needs a microphone that opens: with none it is `Unsupported(AUDIO)`,
 * with one that will not open it is `Hardware`, both before any recorder or file exists, and the
 * engine can still record without audio afterwards. `prepareRecording` answers the same.
 */
class DesktopAudioTest {
    private fun videosIn(home: File): List<File> =
        home.walkTopDown().filter { it.isFile && it.extension == "mp4" }.toList()

    @Test
    fun withNoMicrophoneAudioIsUnsupportedAndNothingIsLeft() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)

            val prepare =
                assertIs<CameraKResult.Failure>(sut.prepareRecording(VideoConfiguration { enableAudio = true }))
            assertEquals(CameraFeature.AUDIO, assertIs<CameraKError.Unsupported>(prepare.error).feature)
            val start = async { sut.startRecording(VideoConfiguration { enableAudio = true }) }
            rig.settle()

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertEquals(CameraFeature.AUDIO, error.feature)
            assertEquals(emptyList(), rig.recorders.toList(), "a recorder was opened")
            assertEquals(emptyList(), videosIn(rig.home), "a file was left")
            assertEquals(emptyList(), events.toList(), "recording events of a start that was refused")

            steps.at(1_000)
            steps.startRecording(sut)
            assertEquals(1, rig.recorders.size, "a recording without audio after the refusal")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aMicrophoneThatWillNotOpenIsHardwareAndNothingIsLeft() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val busy = LineUnavailableException("The microphone is in use")
        rig.microphone = MicrophoneOpener { throw busy }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)

            val start = async { sut.startRecording(VideoConfiguration { enableAudio = true }) }
            rig.settle()

            val error = assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertSame(busy, error.cause)
            assertEquals(emptyList(), rig.recorders.toList(), "a recorder was opened")
            assertEquals(emptyList(), videosIn(rig.home), "a file was left")
            val prepare =
                assertIs<CameraKResult.Failure>(sut.prepareRecording(VideoConfiguration { enableAudio = true }))
            assertIs<CameraKError.Hardware>(prepare.error)
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aStartStoppedWhileItOpensTheMicrophoneClosesItOutsideTheEngineLock() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val closedWithTheLockFree = CompletableDeferred<Boolean>()
        val fake = FakeMicrophone()
        val microphone = object : Microphone by fake {
            // Another thread takes the engine's lock; while this close holds it, that thread waits.
            override fun close() {
                val other = thread { sut.startedWith }
                other.join(1_000)
                closedWithTheLockFree.complete(!other.isAlive)
                fake.close()
            }
        }
        rig.microphone = MicrophoneOpener {
            // A stop while the start opens the microphone: the start then never runs its recording.
            runBlocking { sut.stopRecording() }
            microphone
        }
        try {
            steps.open(sut)

            val start = async { sut.startRecording(VideoConfiguration { enableAudio = true }) }
            rig.settle()

            assertIs<CameraKError.Closed>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertTrue(closedWithTheLockFree.await(), "the microphone was closed under the engine's lock")
            assertEquals(0L, fake.closed.count, "the microphone was left open")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aMicrophoneRefusedAtItsPromptIsPermissionAndNeverOpens() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        var opened = 0
        rig.microphone = accessCheckedOpener({ PermissionStatus.PermanentlyDenied }) {
            opened++
            FakeMicrophone()
        }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)

            val start = async { sut.startRecording(VideoConfiguration { enableAudio = true }) }
            rig.settle()

            val error = assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertEquals(CameraKPermission.MICROPHONE, error.permission)
            assertEquals(PermissionStatus.PermanentlyDenied, error.status)
            assertEquals(0, opened, "the refused microphone was opened")
            assertEquals(emptyList(), rig.recorders.toList(), "a recorder was opened")
            assertEquals(emptyList(), videosIn(rig.home), "a file was left")
            val prepare =
                assertIs<CameraKResult.Failure>(sut.prepareRecording(VideoConfiguration { enableAudio = true }))
            assertIs<CameraKError.Permission>(prepare.error)
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aMicrophoneAllowedAtItsPromptOpens() = runTest {
        var asked = 0
        val microphone = FakeMicrophone()
        val opener = accessCheckedOpener({
            asked++
            null
        }) { microphone }

        assertSame(microphone, opener.open { 0L })
        assertEquals(1, asked)
    }

    @Test
    fun prepareWithAMicrophoneSucceedsAndLeavesItClosed() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val microphone = FakeMicrophone()
        rig.microphone = MicrophoneOpener { microphone }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)

            assertEquals(CameraKResult.Success(Unit), sut.prepareRecording(VideoConfiguration { enableAudio = true }))
            assertEquals(0L, microphone.closed.count, "prepare left the microphone open")
            assertTrue(rig.recorders.isEmpty())
        } finally {
            sut.close()
            rig.settle()
        }
    }
}
