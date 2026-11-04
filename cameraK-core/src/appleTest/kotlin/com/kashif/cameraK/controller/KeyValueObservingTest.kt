package com.kashif.cameraK.controller

import platform.Foundation.NSProgress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * observeKeyPaths, the key-value observer the engine watches a camera's torch and zoom with, over a
 * Foundation object the simulator has: NSProgress, whose completedUnitCount posts a key-value change
 * on the thread that sets it. The observer's callback is added to its Objective-C class at run time, so
 * these cases check that the method is reached at all, once per change, and never after close.
 */
class KeyValueObservingTest {
    @Test
    fun eachChangeOfAnObservedKeyPathIsReported() {
        val progress = NSProgress.progressWithTotalUnitCount(10)
        var reports = 0
        val observation = observeKeyPaths(progress, listOf("completedUnitCount")) { reports++ }

        progress.setCompletedUnitCount(3)
        assertEquals(1, reports, "reports after one change")
        progress.setCompletedUnitCount(4)
        assertEquals(2, reports, "reports after a second change")
        observation.close()
    }

    @Test
    fun nothingIsReportedAfterClose() {
        val progress = NSProgress.progressWithTotalUnitCount(10)
        var reports = 0
        val observation = observeKeyPaths(progress, listOf("completedUnitCount")) { reports++ }
        progress.setCompletedUnitCount(1)

        observation.close()
        observation.close()
        progress.setCompletedUnitCount(2)

        assertEquals(1, reports, "a closed observation was still called")
    }

    @Test
    fun observationsOfOneObjectReachOnlyTheirOwnCallback() {
        val progress = NSProgress.progressWithTotalUnitCount(10)
        var first = 0
        var second = 0
        val closedFirst = observeKeyPaths(progress, listOf("completedUnitCount")) { first++ }
        progress.setCompletedUnitCount(1)
        closedFirst.close()

        val observation = observeKeyPaths(progress, listOf("completedUnitCount", "totalUnitCount")) { second++ }
        progress.setCompletedUnitCount(2)
        progress.setTotalUnitCount(20)

        assertEquals(1, first, "the first observation, closed before the second began")
        assertEquals(2, second, "the second observation, over two key paths")
        observation.close()
    }

    @Test
    fun aThrowingCallbackIsLoggedAndTheObservationGoesOn() {
        val progress = NSProgress.progressWithTotalUnitCount(10)
        var calls = 0
        val observation = observeKeyPaths(progress, listOf("completedUnitCount")) {
            calls++
            throw IllegalStateException("a callback that fails")
        }

        progress.setCompletedUnitCount(1)
        progress.setCompletedUnitCount(2)

        assertEquals(2, calls, "the observation stopped after its callback threw")
        observation.close()
    }
}
