@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.CameraXBackend
import com.kashif.cameraK.engine.contract.BoundSettings
import com.kashif.cameraK.engine.contract.EngineContractDriver
import com.kashif.cameraK.engine.contract.HardwareChange
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.AssumptionViolatedException
import kotlin.time.Duration.Companion.seconds

/**
 * The contract kit's driver for [AndroidCameraEngine] on a device, over real CameraX through
 * [RecordingCameraXBackend] (design §12.4 B1). It cannot script the platform, so every fault hook
 * throws [AssumptionViolatedException] and the case that calls one reports as skipped.
 */
internal class DeviceEngineDriver : EngineContractDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var backend: RecordingCameraXBackend? = null
    private var engine: AndroidCameraEngine? = null

    // Real dispatchers: CameraX binds on the real main looper, so nothing replaces Dispatchers.Main.
    override fun runCase(body: suspend TestScope.() -> Unit): TestResult = runTest(timeout = CASE_TIMEOUT) { body() }

    override fun createEngine(config: CameraConfiguration): AndroidCameraEngine {
        val recording = RecordingCameraXBackend(CameraXBackend.Default)
        backend = recording
        return AndroidCameraEngine(config, instrumentation.targetContext, CameraKEnvironment(), backend = recording)
            .also { engine = it }
    }

    override fun holdOpen() = needsFaultInjection()

    override fun deliverOpen() = needsFaultInjection()

    override fun failOpen() = needsFaultInjection()

    override fun interrupt(reason: InterruptionReason) = needsFaultInjection()

    override fun endInterruption() = needsFaultInjection()

    override fun fail() = needsFaultInjection()

    override fun changeHardware(change: HardwareChange) = needsFaultInjection()

    override fun boundSettings(): BoundSettings = recording().boundSettings()

    override fun releaseCount(): Int = recording().releaseCount()

    /**
     * Idles the main looper, where the engine binds and CameraX posts its observers; until the
     * engine closes, waits for the camera enumeration it starts at construction; then waits for the
     * control calls the engine sent since the last settle. Never sleeps.
     */
    override suspend fun settle() {
        instrumentation.waitForIdleSync()
        val live = checkNotNull(engine) { "settle before createEngine" }
        if (!live.isClosed) live.capabilities.first { it.allLenses.isNotEmpty() }
        recording().awaitControlCalls()
        instrumentation.waitForIdleSync()
    }

    private fun recording(): RecordingCameraXBackend = checkNotNull(backend) { "the driver has created no engine" }

    private fun needsFaultInjection(): Nothing = throw AssumptionViolatedException(FAULT_HOOK)

    private companion object {
        const val FAULT_HOOK = "needs fault injection; not in the device subset"
        val CASE_TIMEOUT = 30.seconds
    }
}
