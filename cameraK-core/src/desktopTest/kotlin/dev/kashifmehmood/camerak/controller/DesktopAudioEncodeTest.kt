package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.video.VideoConfiguration
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.io.File
import java.nio.ShortBuffer
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real FFmpeg encoder: a recording with audio gets an AAC track as long as its video, written by
 * sample count while the video is stamped by capture time; one without audio gets no audio track.
 */
class DesktopAudioEncodeTest {
    private val folder = createTempDirectory("desktop-audio").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun encodeOneSecond(file: File, audio: Pair<Int, Int>?) {
        val recorder = createFrameRecorder(file.path, VideoConfiguration(), audio)
        recorder.imageWidth = 320
        recorder.imageHeight = 240
        recorder.start()
        try {
            val frame = Frame(320, 240, Frame.DEPTH_UBYTE, 3)
            val rate = 48_000
            var written = 0
            for (i in 0 until 30) {
                recorder.timestamp = i * 1_000_000L / 30
                recorder.record(frame)
                if (audio != null) {
                    // 1/30 s of a 440 Hz sine per frame, so the audio keeps pace with the video.
                    val next = (i + 1) * rate / 30
                    val samples = ShortArray(next - written) { n ->
                        (sin(2 * PI * 440 * (written + n) / rate) * 8_000).toInt().toShort()
                    }
                    recorder.record(
                        Frame().apply {
                            sampleRate = rate
                            audioChannels = 1
                            this.samples = arrayOf(ShortBuffer.wrap(samples))
                        },
                    )
                    written = next
                }
            }
            recorder.stop()
        } finally {
            recorder.release()
        }
    }

    @Test
    fun aRecordingWithSamplesHasAnAacTrackAsLongAsItsVideo() {
        val file = File(folder, "with-audio.mp4")
        encodeOneSecond(file, audio = 48_000 to 1)

        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            assertEquals(1, grabber.audioChannels, "audio channels")
            assertTrue("aac" in grabber.audioCodecName.orEmpty(), "audio codec ${grabber.audioCodecName}")
            assertTrue(abs(grabber.lengthInTime - 1_000_000L) <= 100_000L, "length ${grabber.lengthInTime} µs")
            // The container's length is its longest track's; the audio's own is what it decodes to.
            var decoded = 0L
            while (true) {
                val samples = grabber.grabSamples() ?: break
                decoded += samples.samples[0].remaining()
            }
            val audioSeconds = decoded.toDouble() / grabber.sampleRate
            // AAC codes 1024-sample frames and primes the encoder with one: two frames either way.
            assertTrue(abs(audioSeconds - 1.0) <= 2 * 1024.0 / 48_000, "audio $audioSeconds s against 1 s of video")
        }
    }

    @Test
    fun aRecordingWithoutAudioHasNoAudioTrack() {
        val file = File(folder, "video-only.mp4")
        encodeOneSecond(file, audio = null)

        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            assertEquals(0, grabber.audioChannels)
        }
    }
}
