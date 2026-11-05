@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.compose

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The default Compose path keeps its holder in a ViewModel (design §1.5, §7.3): an Activity
 * recreation keeps the holder, its plugins and any recording; leaving for good closes it.
 *
 * Robolectric's ActivityController.recreate() destroys the Activity as a configuration change does,
 * passing the ViewModelStore on to the new instance. The holder runs on an unconfined test dispatcher,
 * so the stop and the start a recreation issues on the main thread each reach the engine before the
 * next one is issued, as they would on a device where neither waits on the other.
 */
@RunWith(RobolectricTestRunner::class)
class RetainedHolderTest {
    private val test = TestScope(UnconfinedTestDispatcher())
    private val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
    private val holderParent = SupervisorJob()
    private val environment = CameraKEnvironment(
        dispatchers = CameraKDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
        timeSource = test.testScheduler.timeSource,
        parentJob = holderParent,
        permissionChecker = FakePermissionChecker(),
    )

    private val engines = mutableListOf<CameraEngine>()
    private val built = mutableListOf<CameraKStateHolder>()
    private var nextEngine: () -> CameraEngine = { FakeCameraEngine() }
    private val factory = CameraEngineFactory { _, _ -> nextEngine().also(engines::add) }
    private var controller: ActivityController<HostActivity>? = null

    @After
    fun tearDown() {
        // A test that left its Activity running would leave its composition to the next test.
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        settle()
        HostActivity.content = null
        holderParent.cancel()
        test.cancel()
    }

    /** The holder the current composition remembered. */
    private var holder: CameraKStateHolder? = null

    private val current: CameraKStateHolder get() = assertNotNull(holder, "nothing was remembered")

    @Composable
    private fun Remembered(
        config: CameraConfiguration = CameraConfiguration(),
        plugins: List<CameraKPlugin> = emptyList(),
        retained: Boolean = true,
    ) {
        holder = rememberCameraKStateHolder(
            config = config,
            plugins = plugins,
            lifecycleOwner = LocalLifecycleOwner.current,
            engineFactory = factory,
            environment = environment,
            viewModelStoreOwner = if (retained) LocalViewModelStoreOwner.current else null,
        ).also { if (built.none { b -> b === it }) built += it }
    }

    private fun launch(content: @Composable () -> Unit) {
        HostActivity.content = content
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        settle()
    }

    private fun recreate() {
        assertNotNull(controller).recreate()
        settle()
    }

    private fun settle() {
        repeat(SETTLE_PASSES) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            test.runCurrent()
        }
    }

    private fun startRecording(): RecordingState.Recording {
        var started: CameraKResult<RecordingState.Recording>? = null
        test.launch { started = current.startRecording() }
        test.runCurrent()
        return assertIs<CameraKResult.Success<RecordingState.Recording>>(started, "the recording").value
    }

    private fun events(of: CameraKStateHolder): List<CameraKEvent> {
        val received = mutableListOf<CameraKEvent>()
        test.launch { of.events.collect { received += it } }
        return received
    }

    private val fake: FakeCameraEngine get() = engines.single() as FakeCameraEngine

    @Test
    fun recreateHostMidRecordingRecordingContinues() {
        launch { Remembered() }
        assertIs<CameraKState.Ready>(current.cameraState.value)
        val first = current
        val recording = startRecording()

        recreate()
        test.advanceTimeBy(5.seconds)
        test.runCurrent()

        assertSame(first, current, "the recreation built a new holder")
        val live = assertIs<RecordingState.Recording>(current.recordingState.value, "the recording did not survive")
        assertEquals(recording.recordingId, live.recordingId)
        assertFalse("stopRecording" in fake.calls, "the recreation stopped the recording: ${fake.calls}")
        assertFalse("stop" in fake.calls, "the camera closed under the recording: ${fake.calls}")
        assertIs<CameraKState.Ready>(current.cameraState.value)
    }

    @Test
    fun disposeHostEmitsRecordingEndedShutdownWithPath() {
        launch { Remembered() }
        val first = current
        val events = events(first)
        val recording = startRecording()

        assertNotNull(controller).pause().stop().destroy()
        settle()

        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(RecordingEndReason.SHUTDOWN, ended.reason)
        assertEquals(recording.filePath, assertIs<CameraKResult.Success<RecordedVideo>>(ended.result).value.path)
        assertEquals(CameraKState.Closed, first.cameraState.value)
        assertEquals(1, fake.closeCalls, "engine calls: ${fake.calls}")
    }

    @Test
    fun configChangeClosesOldHolderBeforeNewStarts() {
        val log = mutableListOf<String>()
        val oldClosing = CompletableDeferred<Unit>()
        val logged = listOf(
            LoggedEngine("old", log) { oldClosing.await() },
            LoggedEngine("new", log) {},
        ).iterator()
        nextEngine = { logged.next() }
        var config by mutableStateOf(CameraConfiguration(aspectRatio = AspectRatio.RATIO_4_3))
        launch { Remembered(config = config) }
        assertIs<CameraKState.Ready>(current.cameraState.value)

        config = CameraConfiguration(aspectRatio = AspectRatio.RATIO_16_9)
        settle()
        assertEquals(listOf("old:start"), log, "the new engine started while the old one was still closing")

        oldClosing.complete(Unit)
        settle()

        assertEquals(listOf("old:start", "old:closed", "new:start"), log)
        assertEquals(2, built.size, "a new configuration did not build a new holder")
        assertIs<CameraKState.Ready>(current.cameraState.value)
    }

    @Test
    fun noViewModelStoreOwnerFallsBackToOnDispose() {
        launch { Remembered(retained = false) }
        val first = current
        assertIs<CameraKState.Ready>(first.cameraState.value)

        recreate()

        assertEquals(CameraKState.Closed, first.cameraState.value, "the old holder was not closed on dispose")
        assertEquals(2, built.size, "without a ViewModelStoreOwner the recreation kept the holder")
        assertIs<CameraKState.Ready>(current.cameraState.value)
        assertEquals(1, (engines.first() as FakeCameraEngine).closeCalls)
    }

    @Test
    fun recreateHostWithoutRecordingKeepsHolderAndReopensTheEngine() {
        launch { Remembered() }
        val first = current
        assertIs<CameraKState.Ready>(first.cameraState.value)

        recreate()

        assertSame(first, current, "the recreation built a new holder")
        assertEquals(1, engines.size, "the engine factory ran again")
        assertEquals(listOf("stop", "start"), fake.calls.takeLast(2), "engine calls: ${fake.calls}")
        assertFalse("close" in fake.calls, "the recreation closed the engine: ${fake.calls}")
        assertIs<CameraKState.Ready>(current.cameraState.value)
    }

    @Test
    fun recreateHostWithNewPluginInstancesReplacesTheOldOnes() {
        val attachments = mutableListOf<Pair<CountingPlugin, StateFlow<CameraKState>>>()
        launch {
            // A recreated composition remembers new instances, as an app's remembered plugins do.
            val plugins = remember<List<CameraKPlugin>> {
                listOf(CountingPlugin("saver", attachments), CountingPlugin("qr", attachments))
            }
            Remembered(plugins = plugins)
        }
        val first = current
        val old = attachments.map { it.first }.distinct()
        assertEquals(2, old.size, "the plugins present at creation did not attach")

        recreate()

        assertSame(first, current, "the recreation built a new holder")
        val new = attachments.map { it.first }.distinct() - old.toSet()
        assertEquals(2, new.size, "the new plugin instances did not attach")
        old.forEach {
            assertEquals(1, it.attaches, "${it.kind} (old) attached ${it.attaches} times")
            assertEquals(1, it.detaches, "${it.kind} (old) detached ${it.detaches} times")
        }
        new.forEach {
            assertEquals(1, it.attaches, "${it.kind} (new) attached ${it.attaches} times")
            assertEquals(0, it.detaches, "${it.kind} (new) detached ${it.detaches} times")
        }
        assertTrue(attachments.all { it.second === first.cameraState }, "a plugin attached to another holder")
        val attachedNow = (old + new).filter { it.attaches > it.detaches }
        assertEquals(listOf("qr", "saver"), attachedNow.map { it.kind }.sorted(), "a plugin type is attached twice")
    }

    /** Records each attach with the camera state of the holder it came from, and each detach. */
    private class CountingPlugin(
        val kind: String,
        private val log: MutableList<Pair<CountingPlugin, StateFlow<CameraKState>>>,
    ) : CameraKPlugin {
        var attaches = 0
        var detaches = 0
        override val needsFrames: Boolean get() = false

        override fun onAttach(context: CameraKPluginContext) {
            attaches++
            log += this to context.cameraState
        }

        override fun onDetach() {
            detaches++
        }
    }

    /** The fake, logging its start and the end of its close into a log both engines share. */
    private class LoggedEngine(
        private val name: String,
        private val log: MutableList<String>,
        private val fake: FakeCameraEngine = FakeCameraEngine(),
        private val closing: suspend () -> Unit,
    ) : CameraEngine by fake {
        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
            log += "$name:start"
            return fake.start(desired)
        }

        override suspend fun closeAndJoin() {
            fake.close()
            closing()
            log += "$name:closed"
        }
    }

    private companion object {
        const val SETTLE_PASSES = 5
        const val FRAME_MS = 20L
    }
}

/** Sets [content] as its own content on every creation, so a recreated instance composes it again. */
class HostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content?.invoke() }
    }

    companion object {
        var content: (@Composable () -> Unit)? = null
    }
}
