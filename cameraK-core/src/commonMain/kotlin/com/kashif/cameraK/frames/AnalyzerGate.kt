package com.kashif.cameraK.frames

import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Admission for one analyzer (one registration), shared by every platform frame source. Pure:
 * it holds no frame and starts no thread. A source asks it before touching a frame, as the sample
 * shows.
 *
 * - **Busy skips, nothing queues.** While the analyzer runs, [tryEnter] returns null; the frame is
 *   skipped, never kept for later. Frames are latest-wins.
 * - **[maxFps] counts attempts.** A throttle window starts at each [tryEnter] that returns a lease,
 *   whatever the analyzer then does; a frame skipped because the analyzer was busy is not an
 *   attempt. Call [tryEnter] before any copy, so a throttled frame costs nothing.
 * - **[maxRetained].** While the analyzer holds that many frames through `retain()`, [tryEnter]
 *   returns null; other registrations, each with its own gate, still receive frames.
 * - **Released exactly once.** The entry and each [AnalyzerRetain] release at most once; a further
 *   call does nothing. `onReleased` runs once per lease, when the callback has returned and every
 *   retain is released, even when the analyzer throws.
 * - **A throwing analyzer** is caught by [AnalyzerLease.deliver], logged with its throwable and
 *   counted in [failures]; the next frame is delivered as usual. A fatal throwable
 *   ([isFatalAnalyzerFailure], the plugin host's rule) propagates instead, after the entry is released.
 *
 * [nanoTime] is a monotonic clock in nanoseconds. Thread-safe: a source calls [tryEnter] on its
 * producer thread while the analyzer releases on its own.
 *
 * @sample com.kashif.cameraK.samples.analyzerGateSample
 */
internal class AnalyzerGate(maxFps: Float?, private val nanoTime: () -> Long, maxRetained: Int = 1) {
    init {
        require(maxFps == null || maxFps > 0f) { "maxFps must be positive or null, not $maxFps" }
    }

    private val minIntervalNanos: Long? = maxFps?.let { (NANOS_PER_SECOND / it).toLong() }
    private val retainLimit = maxRetained.coerceAtLeast(1)
    private val lock = SynchronizedObject()
    private var running = false
    private var retained = 0
    private var lastAttemptNanos: Long? = null
    private var failureCount = 0

    /** The analyzer calls that threw. */
    val failures: Int get() = synchronized(lock) { failureCount }

    /**
     * A lease when the analyzer is idle, under its throttle and below its retain limit; otherwise
     * null, and the source skips this frame. [onReleased] runs once, when the lease is fully released.
     */
    fun tryEnter(onReleased: () -> Unit = {}): AnalyzerLease? = synchronized(lock) {
        if (running || retained >= retainLimit || throttled()) {
            null
        } else {
            lastAttemptNanos = nanoTime()
            running = true
            AnalyzerLease(this, onReleased)
        }
    }

    private fun throttled(): Boolean {
        val last = lastAttemptNanos ?: return false
        return minIntervalNanos != null && nanoTime() - last < minIntervalNanos
    }

    internal fun retain(lease: AnalyzerLease): AnalyzerRetain = synchronized(lock) {
        check(!lease.finished) { "retain() after the frame was released" }
        lease.retains++
        retained++
        AnalyzerRetain(lease)
    }

    /** Releases [lease]'s entry, once; returns true when this release finished the lease. */
    internal fun releaseEntry(lease: AnalyzerLease): Boolean = synchronized(lock) {
        if (lease.entered) {
            lease.entered = false
            running = false
            finish(lease)
        } else {
            false
        }
    }

    /** Releases [handle], once; returns true when this release finished its lease. */
    internal fun releaseRetain(handle: AnalyzerRetain): Boolean = synchronized(lock) {
        if (handle.released) {
            false
        } else {
            handle.released = true
            handle.lease.retains--
            retained--
            finish(handle.lease)
        }
    }

    internal fun recordFailure(failure: Throwable) {
        synchronized(lock) { failureCount++ }
        CameraKLogger.w(TAG, "Frame analyzer threw; the frame is released and the next one is delivered", failure)
    }

    private fun finish(lease: AnalyzerLease): Boolean {
        if (lease.entered || lease.retains > 0 || lease.finished) return false
        lease.finished = true
        return true
    }

    private companion object {
        const val TAG = "CameraK"
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/** One frame's admission through an [AnalyzerGate]: the analyzer's callback, plus each frame it retains. */
internal class AnalyzerLease internal constructor(private val gate: AnalyzerGate, private val onReleased: () -> Unit) {
    // Guarded by the gate's lock.
    internal var entered = true
    internal var retains = 0
    internal var finished = false

    /** The analyzer keeps the frame past its callback; each retain needs its own [AnalyzerRetain.release]. */
    fun retain(): AnalyzerRetain = gate.retain(this)

    /**
     * Releases the entry: the analyzer's callback has returned, so the analyzer is idle again once
     * its retains are below the gate's limit. [deliver] calls it; a second call does nothing, and it
     * never releases a retain.
     */
    fun release() {
        if (gate.releaseEntry(this)) onReleased()
    }

    /**
     * Runs the analyzer's callback once, catches and logs what it throws unless that is fatal
     * ([isFatalAnalyzerFailure]), and then releases the entry, so the analyzer is idle again and the
     * frame is released unless it was retained. A fatal throwable propagates.
     */
    @OptIn(InternalCameraKApi::class)
    fun deliver(analyze: () -> Unit) {
        try {
            analyze()
        } catch (failure: Throwable) {
            if (failure.isFatalAnalyzerFailure()) throw failure
            gate.recordFailure(failure)
        } finally {
            release()
        }
    }

    internal fun releaseRetain(handle: AnalyzerRetain) {
        if (gate.releaseRetain(handle)) onReleased()
    }
}

/**
 * One retain of an [AnalyzerLease]: the frame stays held until [release]. A platform
 * `RetainedFrame.close()` calls its own handle's [release].
 */
internal class AnalyzerRetain internal constructor(internal val lease: AnalyzerLease) {
    // Guarded by the gate's lock.
    internal var released = false

    /** Releases this retain, once; a second call does nothing, and never releases another retain or the entry. */
    fun release() = lease.releaseRetain(this)
}

/**
 * One registration's admission through its own [AnalyzerGate], for a frame source outside this
 * module: camerak-testing's `FakeFrameSource`, which has no access to the gate's internal types. It
 * applies the gate's busy skip, `maxFps` throttle and failure rule, and holds no retain: the frame
 * reaches [deliver]'s callback as the caller passes it.
 */
@InternalCameraKApi
public class AnalyzerAdmission(maxFps: Float?, nanoTime: () -> Long) {
    private val gate = AnalyzerGate(maxFps, nanoTime)

    /**
     * Runs [analyze] once when the gate admits the frame, catching and logging what it throws as
     * [AnalyzerLease.deliver] does; returns false, without running it, when the analyzer is busy or
     * throttled.
     */
    public fun deliver(analyze: () -> Unit): Boolean {
        val lease = gate.tryEnter() ?: return false
        lease.deliver(analyze)
        return true
    }
}
