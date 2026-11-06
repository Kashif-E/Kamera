package com.kashif.mediasaverplugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Auto-save reads the holder's `captures`: every successful `capture()` is saved once, and its
 * outcome is emitted on `results`. A failed save is a value there, not a plugin failure.
 */
class AutoSaveTest : SaverTest() {
    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    private fun TestScope.collectResults(plugin: MediaSaverPlugin): List<CameraKResult<SavedImage>> {
        val results = mutableListOf<CameraKResult<SavedImage>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { plugin.results.collect { results += it } }
        return results
    }

    @Test
    fun autoSavesFromCapturesFlow() = runTest {
        val store = FakeGalleryStore()
        val plugin = saver(
            MediaSaverConfig {
                autoSave = true
                prefix = "Shot"
            },
            store,
        )
        val results = collectResults(plugin)
        val s = saverSession(plugin)
        startReady(s)

        s.holder.capture()
        runCurrent()

        val add = store.adds.single()
        assertEquals(FakeCaptures.image().path, add.path, "the saver did not copy the capture's file")
        assertEquals("Shot_$FIXED_MILLIS", add.name, "the auto-save name")
        val saved = assertIs<CameraKResult.Success<SavedImage>>(results.single(), "the auto-save's outcome").value
        assertEquals("Shot_$FIXED_MILLIS.jpg", saved.displayName)
        assertEquals(PluginStatus.Running, plugin.status.value)
    }

    @Test
    fun saverNeedsNoFrames() = runTest {
        val plugin = saver(MediaSaverConfig { autoSave = true })
        val s = saverSession(plugin)

        startReady(s)

        assertFalse(plugin.needsFrames, "the saver asked for frame analysis")
        assertEquals(false, s.engineConfig()?.frameAnalysis, "a holder with only the saver bound frame analysis")
    }

    @Test
    fun withoutAutoSaveACaptureIsNotSaved() = runTest {
        val store = FakeGalleryStore()
        val plugin = saver(MediaSaverConfig(), store)
        val s = saverSession(plugin)
        startReady(s)

        s.holder.capture()
        runCurrent()

        assertEquals(0, store.adds.size, "a capture was saved with auto-save off")
        assertEquals(PluginStatus.Idle, plugin.status.value)
    }

    @Test
    fun aDetachedSaverSavesNothing() = runTest {
        val store = FakeGalleryStore()
        val plugin = saver(MediaSaverConfig { autoSave = true }, store)
        val s = saverSession(plugin)
        startReady(s)

        s.holder.detachPlugin(plugin)
        s.holder.capture()
        runCurrent()

        assertEquals(0, store.adds.size, "a detached saver saved a capture")
        assertEquals(PluginStatus.Idle, plugin.status.value)
    }

    /** Replaces ImageSaverAutoSaveFailureTest: a failed auto-save is a result, not PluginFailed. */
    @Test
    fun aFailedAutoSaveIsAFailureOnResultsAndTheNextCaptureIsSaved() = runTest {
        val logged = mutableListOf<Throwable?>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, _, throwable -> if (level == CameraKLogger.Level.ERROR) logged += throwable }
        val refusal = IllegalStateException("gallery full")
        val store = FakeGalleryStore()
        val succeed = store.answer
        store.answer = { CameraKResult.Failure(CameraKError.Storage(StorageFailure.FULL, cause = refusal)) }
        val plugin = saver(MediaSaverConfig { autoSave = true }, store)
        val results = collectResults(plugin)
        val s = saverSession(plugin)
        startReady(s)

        s.holder.capture()
        runCurrent()
        assertEquals(PluginStatus.Running, plugin.status.value, "a failed save changed the plugin's status")
        store.answer = succeed
        s.holder.capture()
        runCurrent()

        assertEquals(2, results.size, "outcomes: $results")
        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(results[0]).error)
        assertSame(refusal, error.cause)
        assertIs<CameraKResult.Success<SavedImage>>(results[1], "the next capture was not saved")
        assertEquals(
            emptyList(),
            s.events.filterIsInstance<CameraKEvent.PluginFailed>(),
            "a failed save was a PluginFailed",
        )
        assertEquals(PluginStatus.Running, plugin.status.value)
        assertTrue(logged.any { it === refusal }, "the failed save was not logged with its cause: $logged")
    }

    @Test
    fun aThrowThatEscapesTheSaverMarksItFailed() = runTest {
        val store = FakeGalleryStore()
        val bug = IllegalStateException("store bug")
        store.answer = { throw bug }
        val plugin = saver(MediaSaverConfig { autoSave = true }, store)
        val s = saverSession(plugin)
        startReady(s)

        s.holder.capture()
        runCurrent()

        val failed = assertIs<PluginStatus.Failed>(plugin.status.value, "status after an escaping throw")
        assertSame(bug, failed.error.cause)
        assertEquals("MediaSaverPlugin", s.events.filterIsInstance<CameraKEvent.PluginFailed>().single().pluginId)
    }
}
