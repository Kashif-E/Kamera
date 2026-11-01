package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.QualityPrioritization
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_group_async
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_wait
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AVCaptureSession is not thread-safe: startRunning between begin and commitConfiguration raises
 * NSGenericException, which aborts a Kotlin/Native process. Every session call therefore has to run
 * on one serial queue the controller owns, never on main, a global concurrent queue or the caller.
 */
class SessionQueueTest {
    private val createdLock = NSLock()
    private val created = mutableListOf<RecordingSession>()

    private fun sessions(): List<RecordingSession> {
        createdLock.lock()
        try {
            return created.toList()
        } finally {
            createdLock.unlock()
        }
    }

    private fun newSession(): RecordingSession = RecordingSession().also {
        createdLock.lock()
        try {
            created += it
        } finally {
            createdLock.unlock()
        }
    }

    private fun controller(sessionFactory: () -> CaptureSessionOps = ::newSession) = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        aspectRatio = AspectRatio.RATIO_4_3,
        sessionFactory = sessionFactory,
        inputConfigurator = { _, _ -> true },
    )

    @Test
    fun setupStartAndStopRequestedFromMainRunOnTheControllersSessionQueue() {
        val controller = controller()
        val ready = atomic(false)
        controller.onSessionReady = { ready.value = true }

        controller.setupSession()
        awaitCondition("onSessionReady") { ready.value }
        val session = sessions().single()

        controller.startSession()
        awaitCondition("startRunning") { session.isRunning }
        controller.stopSession()
        awaitCondition("stopRunning") { !session.isRunning }

        assertAllOnOneSessionQueue(session.calls)
    }

    @Test
    fun setupIsOneTransactionWithTheFinalPresetAndTheChangesQueuedBeforeIt() {
        val controller = controller()
        val queuedOutput = AVCaptureVideoDataOutput()
        controller.queueConfigurationChange { controller.safeAddOutput(queuedOutput) }
        controller.onSessionReady = { controller.startSession() }

        controller.setupSession()
        val session = awaitSession()
        awaitCondition("startRunning") { session.isRunning }

        val calls = session.calls
        val beforeStart = calls.takeWhile { it.op != "startRunning" }
        assertEquals(
            listOf("beginConfiguration", "commitConfiguration"),
            beforeStart.map { it.op }.filter { it.endsWith("Configuration") },
            "startup must be exactly one configuration transaction before startRunning; calls: $calls",
        )
        assertEquals(
            listOf<Any?>(AVCaptureSessionPresetPhoto),
            calls.filter { it.op == "setSessionPreset" }.map { it.subject },
            "the 4:3 preset must be set once, inside the startup transaction; calls: $calls",
        )
        val addQueued = calls.single { it.op == "addOutput" && it.subject === queuedOutput }
        assertEquals(1, addQueued.openConfigurations, "a change queued before setup joins the startup transaction")
        assertTrue(
            calls.indexOf(addQueued) < calls.indexOfFirst { it.op == "commitConfiguration" },
            "a change queued before setup is applied before the startup commit; calls: $calls",
        )
        assertAllOnOneSessionQueue(calls)
    }

    @Test
    fun callsFromManyCallerThreadsAreSerialisedOnTheSessionQueue() {
        val controller = controller()
        controller.onSessionReady = { controller.startSession() }
        controller.setupSession()
        val session = awaitSession()
        awaitCondition("startRunning") { session.isRunning }

        val callers = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        fun call(i: Int) = when (i % 6) {
            0 -> AVCaptureVideoDataOutput().let { output ->
                controller.queueConfigurationChange { controller.safeAddOutput(output) }
            }
            // AnalyzerPlugin adds its output directly, outside any queued block.
            1 -> controller.safeAddOutput(AVCaptureMetadataOutput())
            2 -> controller.safeRemoveOutput(session.outputs.lastOrNull() ?: AVCaptureVideoDataOutput())
            3 -> controller.stopSession()
            4 -> controller.startSession()
            else -> controller.switchCamera()
        }

        fun runOnCallers(count: Int, oneAtATime: Boolean) {
            val group = dispatch_group_create()
            repeat(count) { i ->
                dispatch_group_async(group, callers) { call(i) }
                if (oneAtATime) dispatch_group_wait(group, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC.toLong()))
            }
            val waited = dispatch_group_wait(group, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC.toLong()))
            assertEquals(0L, waited, "the caller threads did not return within 10 s")
            // A change applied by a final start runs after everything the callers handed to the session.
            val drained = atomic(false)
            controller.queueConfigurationChange { drained.value = true }
            controller.startSession()
            awaitCondition("the session work requested by the callers") { drained.value }
        }

        // Each kind of call once from a background caller, then a concurrent burst. The first phase is
        // asserted on its own so a controller that runs calls on the caller fails here, before the
        // burst can corrupt its unsynchronised state and abort the test process.
        runOnCallers(count = 6, oneAtATime = true)
        assertAllOnOneSessionQueue(session.calls)
        runOnCallers(count = 48, oneAtATime = false)

        val calls = session.calls
        assertAllOnOneSessionQueue(calls)
        assertEquals(emptyList(), calls.filter { it.overlapped }, "session calls overlapped")
        assertEquals(
            emptyList(),
            calls.filter { it.op == "startRunning" && it.openConfigurations > 0 },
            "startRunning ran inside an open configuration (NSGenericException on a real session)",
        )
        assertEquals(
            emptyList(),
            calls.filter { it.op.startsWith("add") || it.op.startsWith("remove") }
                .filter { it.openConfigurations == 0 },
            "inputs and outputs were changed outside a configuration transaction",
        )
        assertEquals(
            emptyList(),
            calls.filter { it.op == "commitConfiguration" && it.openConfigurations == 0 },
            "commitConfiguration without a matching beginConfiguration",
        )
        assertEquals(0, session.openConfigurations, "a configuration transaction was left open")
    }

    @Test
    fun cleanupWhileSetupIsInFlightNeverPublishesOrStartsTheSession() {
        val inFactory = dispatch_semaphore_create(0)
        val release = dispatch_semaphore_create(0)
        val controller = controller {
            dispatch_semaphore_signal(inFactory)
            dispatch_semaphore_wait(release, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC.toLong()))
            newSession()
        }
        val ready = atomic(false)
        controller.onSessionReady = {
            ready.value = true
            controller.startSession()
        }

        controller.setupSession()
        assertEquals(
            0L,
            dispatch_semaphore_wait(inFactory, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC.toLong())),
            "setup never asked for a session",
        )
        controller.cleanupSession()
        dispatch_semaphore_signal(release)

        val session = awaitSession()
        awaitCondition("the in-flight setup to commit") { session.calls.any { it.op == "commitConfiguration" } }
        // The stale setup's only remaining moves are to publish the session and start it; give it the
        // time it needs to do so, returning early if it does.
        spinFor(seconds = 2.0) { ready.value || session.isRunning }

        assertFalse(ready.value, "onSessionReady fired for a setup that cleanupSession had already superseded")
        assertFalse(session.isRunning, "a session set up after cleanupSession was left running (camera stays on)")
    }

    private fun awaitSession(): RecordingSession {
        awaitCondition("the controller to create its session") { sessions().isNotEmpty() }
        return sessions().single()
    }

    private fun assertAllOnOneSessionQueue(calls: List<RecordingSession.Call>) {
        assertTrue(calls.isNotEmpty(), "no session calls were recorded")
        val onMain = calls.filter { it.onMainThread || it.queue == "com.apple.main-thread" }
        assertEquals(emptyList(), onMain, "session calls ran on the main thread")
        val onGlobal = calls.filter { it.queue.startsWith("com.apple.root.") }
        assertEquals(emptyList(), onGlobal, "session calls ran on a global concurrent queue")
        val offQueue = calls.filter { it.queue.isEmpty() }
        assertEquals(emptyList(), offQueue, "session calls ran on a caller thread outside any dispatch queue")
        assertEquals(1, calls.map { it.queue }.toSet().size, "session calls ran on more than one queue: $calls")
    }

    private fun awaitCondition(what: String, timeoutSeconds: Double = 10.0, condition: () -> Boolean) {
        if (!spinFor(timeoutSeconds, condition)) fail("Timed out after ${timeoutSeconds}s waiting for $what")
    }

    /** Waits until [condition] holds or [seconds] pass, servicing the main queue meanwhile. */
    private fun spinFor(seconds: Double, condition: () -> Boolean): Boolean {
        val deadline = NSDate().timeIntervalSince1970 + seconds
        while (!condition()) {
            if (NSDate().timeIntervalSince1970 > deadline) return false
            if (NSThread.isMainThread) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            } else {
                NSThread.sleepForTimeInterval(0.01)
            }
        }
        return true
    }
}
