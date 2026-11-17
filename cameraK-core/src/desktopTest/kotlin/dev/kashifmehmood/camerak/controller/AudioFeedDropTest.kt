package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.utils.CameraKLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A recording that falls behind the microphone drops chunks. The first drop is logged when it happens;
 * how much was dropped in all is logged once when the microphone closes, so a long stall is not
 * reported as one dropped chunk.
 */
class AudioFeedDropTest {
    private val warnings = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun theDropsOfARecordingThatFellBehindAreCountedWhenTheMicrophoneCloses() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
        val microphone = FakeMicrophone()
        val feed = AudioFeed(microphone, capacityChunks = 1)
        feed.start()

        // 20 ms chunks at 48 kHz; the queue holds one, so the next three are dropped.
        repeat(4) { microphone.deliver(millis = it * 20L) }
        feed.close()

        assertEquals(
            listOf(
                "The recording fell behind the microphone; samples were dropped",
                "The recording fell behind the microphone: 3 chunks (60 ms of audio) were dropped in all",
            ),
            warnings.toList(),
        )
        assertEquals(1, feed.drain().size)
    }
}
