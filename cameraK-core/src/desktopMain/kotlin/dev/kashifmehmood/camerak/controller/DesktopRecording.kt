@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.engine.WorkerDispatcher
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.StorageFailure
import dev.kashifmehmood.camerak.recording.RecordingClock
import dev.kashifmehmood.camerak.recording.RecordingEndReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.utils.CameraKLogger
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameRecorder
import java.io.File
import java.nio.ShortBuffer
import kotlin.time.Duration.Companion.microseconds

/** What a recording tells its engine, under the engine's lock. */
internal interface RecordingListener {
    /** The recorder started. False when the engine no longer wants it: it is then dropped without an event. */
    fun onStarted(recording: DesktopRecording): Boolean

    /** The recording's one end, with the file finalized (or deleted). */
    fun onFinalized(recording: DesktopRecording, result: CameraKResult<RecordedVideo>, reason: RecordingEndReason)
}

/**
 * One desktop recording (§5). A coroutine of its own, on its own worker thread, owns the recorder
 * from `start()` to `release()`: it is the only code that calls them, so `stop()` never runs while
 * `record()` encodes. It writes the producer's leased frames (§8 desktop: each lease given back once
 * the frame is written) as they are: the encoder is handed the slot's own bytes
 * ([LeasedFrame.recordable]), so the recording adds no conversion or copy of its own, and the
 * producer's one blit is the only one. Each frame is stamped by the common [RecordingClock], so a
 * pause is left out of the file, and enforces both limits itself: `maxDuration` on the recorded
 * time and `maxFileSizeBytes` on the file's length after each frame.
 *
 * Every end goes through one path: an app stop ([requestStop]: USER, or SHUTDOWN from the engine's
 * stop or close), a limit, the camera's frames ending (INTERRUPTED) or a recorder error (ERROR).
 * The first reason wins. The file is playable when at least one frame was written and `stop()`
 * closed the container (§4.1): a playable file is `Success`, except on a full disk, and an
 * unplayable one is deleted.
 */
internal class DesktopRecording(
    val path: String,
    private val config: VideoConfiguration,
    private val recorder: FrameRecorder,
    private val nanoClock: () -> Long,
    private val diskFull: (File) -> Boolean,
    private val listener: RecordingListener,
    microphone: Microphone? = null,
) {
    // The microphone's samples, for a recording with audio; closed with the recorder.
    private val audio: AudioFeed? = microphone?.let(::AudioFeed)

    /** Whether the file gets an audio track. */
    val hasAudio: Boolean get() = audio != null

    private val endReason = atomic<RecordingEndReason?>(null)
    private val started = CompletableDeferred<CameraKResult<String>>()

    /** The recording's outcome, completed once the recorder is released. */
    val finalized = CompletableDeferred<CameraKResult<RecordedVideo>>()

    // Set by the recording's one end: its finish, or the engine giving up on it.
    private val endClaimed = atomic(false)

    @Volatile private var clock: RecordingClock? = null
    private lateinit var job: Job

    @Volatile private var ran = false

    // Audio drained but captured after the last frame written: the recording's thread only.
    private val heldAudio = ArrayDeque<AudioChunk>()

    // Guarded by the engine's lock, which pause and resume run under.
    private var paused = false

    // Written only by the recording's coroutine.
    private var framesWritten = 0
    private var lastFrameNumber = -1L
    private var lastTimestampMicros = 0L

    // Per channel; written only by the recording's coroutine.
    private var audioFramesWritten = 0L
    private val audioFrame = Frame()

    /** Whether the recorder started and the engine announced it. */
    val isStarted: Boolean get() = clock != null && started.isCompleted

    /** Whether the engine emitted `Started` for it, which a `Finalized` then follows; false if it never will. */
    @Volatile var announced = false
        private set

    /**
     * Readies the recording's coroutine in [scope], on [worker], which it closes when it ends: once
     * [start]ed, it starts the recorder at [frameSize] (the producer's, so no frame is rescaled),
     * then opens a feed of the producer's frames ([openFeed]) and writes them until the recording
     * ends. Called before the engine lists the recording, so [requestStop] always reaches it.
     */
    fun prepare(
        scope: CoroutineScope,
        worker: WorkerDispatcher,
        openFeed: () -> FrameFeed,
        frameSize: Pair<Int, Int>?,
    ) {
        job = scope.launch(worker.dispatcher, start = CoroutineStart.LAZY) { run(openFeed, frameSize) }
        job.invokeOnCompletion {
            worker.close()
            // Cancelled before it ran (a stop or close during the start): nothing was started.
            if (!ran) {
                release()
                deleteFile()
            }
            val closed = CameraKResult.Failure(CameraKError.Closed)
            started.complete(closed)
            finalized.complete(closed)
        }
    }

    /** Returns the start's outcome: the path once the recorder started and the engine announced it. */
    suspend fun start(): CameraKResult<String> {
        job.start()
        return started.await()
    }

    /** Ends the recording for [reason] unless it is already ending; returns at once. */
    fun requestStop(reason: RecordingEndReason) {
        endReason.compareAndSet(null, reason)
        job.cancel()
    }

    /**
     * Ends a recording whose recorder never finished stopping, with [error]: an announced recording's
     * one `Finalized` is that failure with reason ERROR. Its thread is left to end by itself, and
     * whatever it finishes with later is dropped. Does nothing once the recording has ended.
     */
    fun abandon(error: CameraKError) {
        if (!endClaimed.compareAndSet(false, true)) return
        val failure = CameraKResult.Failure(error)
        if (announced) listener.onFinalized(this, failure, RecordingEndReason.ERROR)
        started.complete(failure)
        finalized.complete(failure)
    }

    /** Pauses the clock at the engine's time; true when it was recording, false when already paused. */
    fun pause(): Boolean {
        val clock = clock ?: return false
        if (paused) return false
        clock.pause(nanoClock())
        paused = true
        return true
    }

    /** Resumes the clock at the engine's time; true when it was paused. */
    fun resume(): Boolean {
        val clock = clock ?: return false
        if (!paused) return false
        clock.resume(nanoClock())
        paused = false
        return true
    }

    private suspend fun run(openFeed: () -> FrameFeed, frameSize: Pair<Int, Int>?) {
        ran = true
        var failure: Exception? = null
        var feed: FrameFeed? = null
        try {
            if (!startRecorder(frameSize)) return
            val frames = openFeed().also { feed = it }
            var limited = false
            while (!limited) {
                // A recording behind the camera always finds a frame waiting, which next() returns
                // without suspending, where a stop's cancellation is otherwise seen.
                currentCoroutineContext().ensureActive()
                val frame = frames.next() ?: break
                limited = try {
                    writeAudio(before = clock?.recordedMicrosAt(frame.captureNanos)) || write(frame)
                } finally {
                    frame.release()
                }
            }
            // Frames ending before a limit or a stop is the camera's cut.
            if (!limited) endReason.compareAndSet(null, RecordingEndReason.INTERRUPTED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Writing a recording frame failed; the recording ends", e)
            failure = e
            endReason.compareAndSet(null, RecordingEndReason.ERROR)
        } finally {
            feed?.cancel()
            if (started.isCompleted && clock != null) withContext(NonCancellable) { finish(failure) }
        }
    }

    // False when the recorder did not start or the engine dropped it; the start's outcome is then set.
    private fun startRecorder(frameSize: Pair<Int, Int>?): Boolean {
        frameSize?.let { (width, height) ->
            recorder.imageWidth = width
            recorder.imageHeight = height
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Starting the recorder failed", e)
            release()
            deleteFile()
            val failure = CameraKResult.Failure(startFailure(e))
            started.complete(failure)
            finalized.complete(failure)
            return false
        }
        clock = RecordingClock(nanoClock())
        audio?.start()
        if (!listener.onStarted(this)) {
            stopAndRelease()
            deleteFile()
            clock = null
            started.complete(CameraKResult.Failure(CameraKError.Closed))
            finalized.complete(CameraKResult.Failure(CameraKError.Closed))
            return false
        }
        announced = true
        started.complete(CameraKResult.Success(path))
        return true
    }

    private fun startFailure(e: Exception): CameraKError =
        JavaCvErrorMapper.recordingFailure(e, playable = false, path, diskFull(File(path).absoluteFile.parentFile))
            ?: CameraKError.Internal(e)

    /** Writes [frame] if it belongs to the recording; true when a limit ends the recording. */
    private fun write(frame: LeasedFrame): Boolean {
        // Frames captured while paused, or not after the previous one, have no timestamp.
        val timestamp = clock?.timestampMicros(frame.captureNanos) ?: return false
        val maxDuration = config.maxDuration?.takeIf { it.isPositive() }
        if (maxDuration != null && timestamp >= maxDuration.inWholeMicroseconds) {
            endReason.compareAndSet(null, RecordingEndReason.MAX_DURATION)
            return true
        }
        // The encoder rounds each timestamp to a frame number at its frame rate. A frame that rounds
        // to the previous frame's number would repeat its pts, which the encoder rejects, so it is
        // left out: a camera faster than the recording's frame rate is recorded at that rate.
        val frameNumber = frameNumberAt(timestamp, recorder.frameRate)
        if (frameNumber <= lastFrameNumber) return false
        lastFrameNumber = frameNumber
        recorder.timestamp = timestamp
        recorder.record(frame.recordable)
        framesWritten++
        lastTimestampMicros = timestamp
        val maxSize = config.maxFileSizeBytes?.takeIf { it > 0 }
        if (maxSize != null && File(path).length() >= maxSize) {
            endReason.compareAndSet(null, RecordingEndReason.MAX_FILE_SIZE)
            return true
        }
        return false
    }

    private fun finish(failure: Exception?) {
        finishAudio()
        val stopFailure = stopAndRelease()
        val playable = framesWritten > 0 && stopFailure == null
        val endFailure = failure ?: stopFailure
        var reason = endReason.value ?: RecordingEndReason.SHUTDOWN
        val result: CameraKResult<RecordedVideo> = when {
            endFailure != null -> {
                reason = RecordingEndReason.ERROR
                JavaCvErrorMapper.recordingFailure(
                    endFailure,
                    playable,
                    path,
                    diskFull(File(path).absoluteFile.parentFile),
                )
                    ?.let { CameraKResult.Failure(it) }
                    ?: CameraKResult.Success(video())
            }
            playable -> CameraKResult.Success(video())
            // A camera cut before any frame, as Android's ERROR_SOURCE_INACTIVE without a file.
            reason == RecordingEndReason.INTERRUPTED -> CameraKResult.Failure(
                CameraKError.Hardware(null, IllegalStateException("The camera stopped before any frame was recorded")),
            )
            // No frame was written, as Android's ERROR_NO_VALID_DATA.
            else -> {
                reason = RecordingEndReason.ERROR
                CameraKResult.Failure(
                    CameraKError.Internal(IllegalStateException("The recording ended before any frame was written")),
                )
            }
        }
        if (!endClaimed.compareAndSet(false, true)) {
            CameraKLogger.w(TAG, "A recorder finished stopping after the engine gave up on it; its outcome is dropped")
            return
        }
        val kept = (result as? CameraKResult.Failure)?.error?.let {
            it is CameraKError.Storage &&
                it.keptFilePath != null
        }
        if (kept == false) deleteFile()
        listener.onFinalized(this, result, reason)
        finalized.complete(result)
    }

    private fun video() = RecordedVideo(
        path = path,
        duration = lastTimestampMicros.microseconds,
        width = recorder.imageWidth,
        height = recorder.imageHeight,
        bitrateBps = config.quality.bitrateBps,
        hasAudio = hasAudio,
    )

    /** Stops then releases the recorder; the first failure (logged), or null. */
    private fun stopAndRelease(): Exception? {
        var failure: Exception? = null
        try {
            recorder.stop()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Stopping the recorder failed", e)
            failure = e
        }
        return release() ?: failure
    }

    private fun release(): Exception? = try {
        audio?.close()
        recorder.release()
        null
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "Releasing the recorder failed", e)
        e
    }

    /** Writes the microphone's queued samples; true when the microphone failed, which ends the recording. */
    /**
     * Writes the queued audio captured before [before], the recorded time of the frame about to be
     * written, and holds the rest for a later frame: frames can reach the recording well after they
     * were captured, and audio written past the last frame would outlast the video. A frame inside a
     * pause (null) writes none.
     */
    private fun writeAudio(before: Long?): Boolean {
        val feed = audio ?: return false
        // Read before the drain: every chunk queued before the failure is then written or held.
        val failed = feed.failure
        heldAudio.addAll(feed.drain())
        while (heldAudio.isNotEmpty()) {
            val micros = clock?.recordedMicrosAt(heldAudio.first().captureNanos)
            if (micros != null && (before == null || micros >= before)) break
            // Inside a pause or before the start (null): writeChunk drops it.
            writeChunk(feed, heldAudio.removeFirst())
        }
        if (failed == null) return false
        endReason.compareAndSet(null, RecordingEndReason.INTERRUPTED)
        return true
    }

    /**
     * Writes [chunk] where the recording's clock puts it. Audio is stamped by sample count, so a chunk
     * that would land more than [ALIGNMENT_MILLIS] after the samples written so far is preceded by
     * silence (a microphone that starts late), and one that would land that far before them is
     * dropped. A chunk from before the start or from inside a pause is dropped.
     */
    private fun writeChunk(feed: AudioFeed, chunk: AudioChunk) {
        val micros = clock?.recordedMicrosAt(chunk.captureNanos) ?: return
        val expected = micros * feed.sampleRate / 1_000_000
        val tolerance = feed.sampleRate.toLong() * ALIGNMENT_MILLIS / 1_000
        if (audioFramesWritten - expected > tolerance) return
        if (expected - audioFramesWritten > tolerance) writeSilence(feed, expected - audioFramesWritten)
        recordSamples(feed, chunk.samples, chunk.count)
    }

    private fun writeSilence(feed: AudioFeed, frames: Long) {
        var left = frames
        val silence = ShortArray(feed.sampleRate * feed.channels)
        while (left > 0) {
            val now = minOf(left, feed.sampleRate.toLong()).toInt()
            recordSamples(feed, silence, now * feed.channels)
            left -= now
        }
    }

    private fun recordSamples(feed: AudioFeed, samples: ShortArray, count: Int) {
        audioFrame.sampleRate = feed.sampleRate
        audioFrame.audioChannels = feed.channels
        audioFrame.samples = arrayOf(ShortBuffer.wrap(samples, 0, count))
        recorder.record(audioFrame)
        audioFramesWritten += count / feed.channels
    }

    /**
     * Closes the microphone, writes what it had queued up to the last frame (the audio ends with the
     * video, within `maxDuration`), then silence up to that frame, so the audio track spans the video.
     * A failure here keeps the file as it is.
     */
    private fun finishAudio() {
        val feed = audio ?: return
        feed.close()
        try {
            val beforeLastFrame = (heldAudio + feed.drain()).filter { chunk ->
                clock?.recordedMicrosAt(chunk.captureNanos)?.let { it < lastTimestampMicros } == true
            }
            beforeLastFrame.forEach { writeChunk(feed, it) }
            if (framesWritten > 0) {
                val end = lastTimestampMicros * feed.sampleRate / 1_000_000
                val tolerance = feed.sampleRate.toLong() * ALIGNMENT_MILLIS / 1_000
                if (end - audioFramesWritten > tolerance) writeSilence(feed, end - audioFramesWritten)
            }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Writing the recording's last audio failed; its audio ends early", e)
        }
    }

    private fun deleteFile() {
        val file = File(path)
        if (file.exists() && !file.delete()) {
            CameraKLogger.w(TAG, "The file of a recording that did not finish could not be deleted")
        }
    }

    private companion object {
        const val TAG = "CameraK"

        // Audio is put back on the clock only when it drifts further than one 20 ms chunk.
        const val ALIGNMENT_MILLIS = 20L
    }
}

/** A storage failure with nothing kept, for an output folder that cannot be created. */
internal fun notWritable(cause: Throwable): CameraKError =
    CameraKError.Storage(StorageFailure.NOT_WRITABLE, null, cause)
