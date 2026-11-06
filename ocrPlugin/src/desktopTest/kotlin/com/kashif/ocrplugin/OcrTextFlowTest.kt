package com.kashif.ocrPlugin

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Recognised text reaches every collector of [OcrPlugin.results]. */
class OcrTextFlowTest {
    @Test
    fun twoCollectors_bothReceiveEachRecognisedText() = runTest {
        val plugin = OcrPlugin(OcrConfig(), FakeRecognizers())
        val session = ocrSession(plugin)
        var first: String? = null
        var second: String? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            first = plugin.results.first().text
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            second = plugin.results.first().text
        }
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame())

        assertEquals(FakeRecognizers.TEXT, first)
        assertEquals(FakeRecognizers.TEXT, second, "the second collector got nothing")
    }
}
