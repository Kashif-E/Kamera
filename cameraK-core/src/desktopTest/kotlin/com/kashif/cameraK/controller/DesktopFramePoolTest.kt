@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.RetainedFrame
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The producer's frame pool is sized from the registrations (W7b decision, desktop must-fix 1): the
 * sum of every registration's `maxRetained`, one slot each for the preview, a still, the recorder and
 * every analyzer, and two of headroom. An analyzer holding its `maxRetained` frames is skipped alone;
 * the preview, the recording and every other analyzer keep getting frames.
 */
class DesktopFramePoolTest {
    @Test
    fun aRetainingAnalyzerAtItsMaxRetainedStallsNoOtherConsumer() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val kept = CopyOnWriteArrayList<RetainedFrame>()
        val other = CopyOnWriteArrayList<Long>()
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec(maxRetained = RETAINED)) { frame -> kept += frame.retain() }
            sut.frames.register(FrameSpec()) { frame -> other += frame.timestampNanos }

            repeat(RETAINED) { steps.frameAt(frameMillis(it)) }

            assertEquals(RETAINED, kept.size, "frames the retaining analyzer kept")
            assertEquals(times(0 until RETAINED), other.toList(), "the second analyzer's frames")
            assertEquals(
                times(RETAINED - 1 until RETAINED).single(),
                sut.preview.frames.value?.timestampNanos,
                "the preview's last frame",
            )

            // At its maxRetained the analyzer is skipped alone.
            repeat(MORE) { steps.frameAt(frameMillis(RETAINED + it)) }

            assertEquals(RETAINED, kept.size, "frames reaching the analyzer at its maxRetained")
            assertEquals(times(0 until RETAINED + MORE), other.toList(), "the second analyzer's frames")
            assertEquals(
                times(RETAINED + MORE - 1 until RETAINED + MORE).single(),
                sut.preview.frames.value?.timestampNanos,
                "the preview's last frame",
            )
            // 30 retained, one each for the preview, a still, the recorder and the two analyzers, two spare.
            val size = RETAINED + 3 + 2 + 2
            assertTrue(sut.grabber.slotsAllocated <= size, "slots allocated: ${sut.grabber.slotsAllocated}")
        } finally {
            kept.forEach { it.close() }
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aRetainingAnalyzerAtItsMaxRetainedStallsNoRecording() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val kept = CopyOnWriteArrayList<RetainedFrame>()
        val other = AtomicInteger(0)
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec(maxRetained = RETAINED)) { frame -> kept += frame.retain() }
            sut.frames.register(FrameSpec()) { other.incrementAndGet() }
            steps.at(1_000)
            steps.startRecording(sut)

            repeat(RETAINED + MORE) { steps.frameAt(frameMillis(it)) }
            val stopped = steps.stopRecording(sut)

            assertIs<CameraKResult.Success<RecordedVideo>>(stopped, "the recording")
            assertEquals(RETAINED + MORE, rig.recorders.single().recordCount.get(), "frames recorded")
            assertEquals(RETAINED, kept.size, "frames the retaining analyzer kept")
            assertEquals(RETAINED + MORE, other.get(), "the second analyzer's frames")
            assertEquals(
                times(RETAINED + MORE - 1 until RETAINED + MORE).single(),
                sut.preview.frames.value?.timestampNanos,
                "the preview's last frame",
            )
        } finally {
            kept.forEach { it.close() }
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun thePoolShrinksWhenARetainingRegistrationCloses() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val kept = CopyOnWriteArrayList<RetainedFrame>()
        try {
            steps.open(sut)
            val retaining = sut.frames.register(FrameSpec(maxRetained = RETAINED)) { frame -> kept += frame.retain() }
            repeat(RETAINED) { steps.frameAt(frameMillis(it)) }
            assertTrue(sut.grabber.slotsAllocated >= RETAINED, "slots while $RETAINED frames are retained")

            retaining.close()
            kept.forEach { it.close() }
            repeat(MORE) { steps.frameAt(frameMillis(RETAINED + it)) }

            // No registration: one slot each for the preview, a still and the recorder, two spare.
            assertTrue(sut.grabber.slotsAllocated <= 5, "slots once nothing is retained: ${sut.grabber.slotsAllocated}")
        } finally {
            kept.forEach { it.close() }
            sut.close()
            rig.settle()
        }
    }

    private fun frameMillis(index: Int): Long = 1_040L + index * 40L

    private fun times(indices: IntRange): List<Long> =
        indices.map { frameMillis(it) * DesktopRecordingSteps.NANOS_PER_MILLI }

    private companion object {
        const val RETAINED = 30
        const val MORE = 3
    }
}
