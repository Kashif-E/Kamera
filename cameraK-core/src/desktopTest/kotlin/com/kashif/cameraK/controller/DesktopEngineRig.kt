@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.BackoffPolicy
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineRuntime
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.engine.WorkerDispatcher
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A desktop engine on the test scheduler, with its producer on real threads the rig can see. The
 * engine's own coroutines (enumeration, watchdog, recovery) run on virtual time; each grab loop
 * runs on a worker thread from [workers], over [camera] or another camera from [newCamera].
 *
 * [settle] is the desktop driver's quiescence: it waits until every producer thread is idle or
 * parked in a camera's `grab()` with nothing to deliver, runs the scheduler's current tasks, and
 * repeats until neither side moves. It never sleeps and never advances virtual time.
 */
internal class DesktopEngineRig(
    private val scheduler: TestCoroutineScheduler,
    private val backoff: BackoffPolicy = BackoffPolicy { Duration.ZERO },
    private val tracer: Tracer = Tracer.None,
) {
    val workers = TrackedWorkers()
    private val cameras = CopyOnWriteArrayList<ScriptedFrameGrabber>()
    val camera = newCamera()

    /** Another camera whose parked grabs [settle] counts as quiet. */
    fun newCamera(): ScriptedFrameGrabber = ScriptedFrameGrabber(workers).also { cameras += it }

    /** The device the engine last opened the camera on, as a grabber factory sees it. */
    @Volatile var openedDeviceId: String? = null
        private set

    val source = object : GrabberSource {
        override fun deviceKey(device: LensInfo): Any = camera

        override val canRelax: Boolean = false

        override fun create(device: LensInfo, constrained: Boolean): FrameGrabber {
            openedDeviceId = device.id
            return camera
        }
    }

    fun environment(): CameraKEnvironment {
        val dispatcher = StandardTestDispatcher(scheduler)
        val dispatchers = CameraKDispatchers(main = dispatcher, default = dispatcher, io = dispatcher)
        return CameraKEnvironment(dispatchers = dispatchers).also {
            it.runtime = EngineRuntime(newWorker = workers::newWorker, backoff = backoff, tracer = tracer)
        }
    }

    /**
     * The OS's authorization and devices are the host's own, so the rig's engines read a fixed
     * Granted and enumerate [enumerate] (the one default device unless a test names others).
     */
    fun engine(
        config: CameraConfiguration = CameraConfiguration(),
        enumerate: () -> CameraCapabilities = { desktopCapabilities() },
        wrap: (GrabLoopListener) -> GrabLoopListener = { it },
    ): DesktopCameraEngine = DesktopCameraEngine(
        config,
        environment(),
        source,
        authorization = { PermissionStatus.Granted },
        listenerWrapper = wrap,
        enumerate = enumerate,
    )

    fun settle() {
        repeat(MAX_ROUNDS) {
            workers.awaitQuiet(cameras)
            val before = workers.epoch
            scheduler.runCurrent()
            workers.awaitQuiet(cameras)
            if (workers.epoch == before) return
        }
        throw AssertionError("the engine never settled in $MAX_ROUNDS rounds")
    }

    private companion object {
        const val MAX_ROUNDS = 1_000
    }
}

/**
 * Producer threads whose tasks are counted, so a test can wait until none runs except one parked
 * in the camera's `grab()`. Every count change and every camera state change bumps [epoch].
 */
internal class TrackedWorkers {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var queued = 0
    private var running = 0

    @Volatile var epoch = 0L
        private set

    fun newWorker(name: String): WorkerDispatcher {
        val executor = object : ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ) {
            override fun execute(command: Runnable) {
                change { queued++ }
                try {
                    super.execute(command)
                } catch (e: RejectedExecutionException) {
                    change { queued-- }
                    throw e
                }
            }

            override fun beforeExecute(t: Thread, r: Runnable) = change {
                queued--
                running++
            }

            override fun afterExecute(r: Runnable, t: Throwable?) = change { running-- }
        }
        return WorkerDispatcher(executor.asCoroutineDispatcher()) { executor.shutdown() }
    }

    fun bump() = change {}

    /**
     * Blocks until no producer task is queued and every running one is parked in one of [cameras]'
     * `grab()`. The cameras are read outside this lock (each bumps [epoch] under its own), and an
     * unchanged epoch shows the read is still current.
     */
    fun awaitQuiet(cameras: List<ScriptedFrameGrabber>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUIET_TIMEOUT_SECONDS)
        while (true) {
            val seen = epoch
            val parked = cameras.sumOf { it.parkedGrabs() }
            lock.withLock {
                if (epoch == seen) {
                    if (queued == 0 && running <= parked) return
                    val left = deadline - System.nanoTime()
                    if (left <= 0) {
                        throw AssertionError("the producer never went quiet: $queued queued, $running running")
                    }
                    changed.awaitNanos(left)
                }
            }
        }
    }

    private inline fun change(update: () -> Unit) = lock.withLock {
        update()
        epoch++
        changed.signalAll()
    }

    private companion object {
        const val QUIET_TIMEOUT_SECONDS = 10L
    }
}

/**
 * A camera that delivers exactly the frames a test lets through. Each `start()` lets one frame
 * through unless [holdOpen] is set, so an engine opens on its own; [deliverFrame] lets one more
 * through, and [failGrab] makes the waiting (or next) `grab()` throw. `grab()` waits the way a real
 * grabber blocks for the next frame, and an interrupt ends the wait with InterruptedException.
 */
internal class ScriptedFrameGrabber(private val workers: TrackedWorkers) : FrameGrabber() {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val frame = Frame(64, 48, Frame.DEPTH_UBYTE, 3)
    private var permits = 0
    private var failure: Throwable? = null
    private var waiter: Thread? = null

    @Volatile var holdOpen = false

    @Volatile var startFailure: Exception? = null

    @Volatile var startCount = 0
        private set

    @Volatile var releaseCount = 0
        private set

    /** Started and not released since: a grab loop owns it. */
    @Volatile var isOpen = false
        private set

    override fun start() {
        startFailure?.let { throw it }
        lock.withLock {
            startCount++
            isOpen = true
            if (!holdOpen) permits++
        }
        workers.bump()
    }

    override fun stop() = Unit

    override fun trigger() = Unit

    override fun grab(): Frame {
        lock.withLock {
            waiter = Thread.currentThread()
            workers.bump()
            try {
                while (permits == 0 && failure == null) available.await()
            } finally {
                waiter = null
            }
            failure?.let {
                failure = null
                throw it
            }
            permits--
        }
        workers.bump()
        return frame
    }

    override fun release() {
        lock.withLock {
            releaseCount++
            isOpen = false
            permits = 0
            failure = null
        }
        workers.bump()
    }

    fun deliverFrame() = signal { permits++ }

    fun failGrab(throwable: Throwable) = signal { failure = throwable }

    /**
     * Grabs waiting with nothing to deliver: 0 or 1. A waiter whose thread was interrupted (the
     * engine cancelled its grab loop) is about to wake, so it is not parked.
     */
    fun parkedGrabs(): Int = lock.withLock {
        val parked = waiter?.takeIf { !it.isInterrupted }
        if (parked != null && permits == 0 && failure == null) 1 else 0
    }

    private inline fun signal(update: () -> Unit) {
        lock.withLock {
            update()
            available.signalAll()
        }
        workers.bump()
    }
}
