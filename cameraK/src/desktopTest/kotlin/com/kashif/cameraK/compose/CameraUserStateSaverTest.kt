@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The user's camera choices survive process death (design §1.5, §7.3; w7-design-decisions.md G5;
 * w8 CF' items 1 and 2): rememberCameraKStateHolder saves lens, device type, flash, torch and zoom with
 * rememberSaveable, and a holder built in a new process starts with them. A configuration with no lens
 * stays on the platform default until the user picks a camera (§6.1 "Default lens").
 */
class CameraUserStateSaverTest {
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** One process: a composition under [registry], holding a holder over [fake]. */
    private class Process(
        scope: TestScope,
        val fake: FakeCameraEngine,
        val registry: SaveableStateRegistry,
        config: CameraConfiguration = CameraConfiguration(),
    ) {
        private val host = Host()
        private val environment = with(scope) { cameraKTestEnvironment() }
        var holder: CameraKStateHolder? = null

        /** The configuration the engine was built with: the app's, seeded with what was saved. */
        var engineConfig: CameraConfiguration? = null
        private val scene = ImageComposeScene(width = 10, height = 10) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                holder = rememberCameraKStateHolder(
                    config = config,
                    plugins = emptyList(),
                    lifecycleOwner = host,
                    engineFactory = { c, _ ->
                        engineConfig = c
                        fake
                    },
                    environment = environment,
                    viewModelStoreOwner = null,
                )
            }
        }

        fun frame(scope: TestScope) {
            Snapshot.sendApplyNotifications()
            scene.render()
            scope.runCurrent()
        }

        fun close() = scene.close()
    }

    // Both cameras offer flash and torch here, so every choice can differ from the configuration's.
    // A back ultra-wide camera, listed after the wide one, makes the device type a choice too.
    private fun capabilities(): CameraCapabilities {
        val base = FakeCapabilities.backAndFront()
        val ultraWide = LensInfo(ULTRA_WIDE_ID, CameraDeviceType.ULTRA_WIDE, CameraLens.BACK, 0.5f, 1f, false, false)
        return CameraCapabilities(
            allLenses = base.allLenses + ultraWide,
            flashModes = (base.allLenses + ultraWide).associate { it.id to setOf(FlashMode.OFF, FlashMode.ON) },
            torchModes = (base.allLenses + ultraWide).associate { it.id to setOf(TorchMode.OFF, TorchMode.ON) },
            supportsPauseResume = base.supportsPauseResume,
            supportedVideoQualities = base.supportedVideoQualities,
            supportsVideoBitrate = base.supportsVideoBitrate,
            supportedAspectRatios = base.supportedAspectRatios,
            supportedImageFormats = base.supportedImageFormats,
            supportedAnalysisFormats = base.supportedAnalysisFormats,
            supportsSquareCapture = base.supportsSquareCapture,
        )
    }

    @Test
    fun restoresLensFlashTorchZoomAfterProcessDeath() = runTest {
        val first = Process(this, FakeCameraEngine(capabilities()), SaveableStateRegistry(null) { true })
        val saved: Map<String, List<Any?>>
        try {
            first.frame(this)
            val holder = assertNotNull(first.holder, "nothing was remembered")
            assertIs<CameraKState.Ready>(holder.cameraState.value)
            assertIs<CameraKResult.Success<*>>(holder.selectCamera(CameraLens.FRONT))
            assertIs<CameraKResult.Success<*>>(holder.setFlashMode(FlashMode.ON))
            assertIs<CameraKResult.Success<*>>(holder.setTorchMode(TorchMode.ON))
            assertIs<CameraKResult.Success<*>>(holder.setZoom(2f))
            // The platform reports what it applied; the camera came with the switch.
            first.fake.reportFlash(FlashMode.ON)
            first.fake.reportTorch(TorchMode.ON)
            first.fake.reportZoom(2f, minRatio = 1f, maxRatio = 4f)
            first.frame(this)
            first.frame(this)
            saved = first.registry.performSave()
        } finally {
            first.close()
        }

        val restoredFake = FakeCameraEngine(capabilities())
        val second = Process(this, restoredFake, SaveableStateRegistry(saved) { true })
        try {
            second.frame(this)
            assertIs<CameraKState.Ready>(assertNotNull(second.holder).cameraState.value)

            assertEquals(FakeCapabilities.FRONT_ID, restoredFake.appliedCameraId, "the lens was not restored")
            assertEquals(FlashMode.ON, restoredFake.appliedFlashMode, "the flash mode was not restored")
            assertEquals(TorchMode.ON, restoredFake.appliedTorchMode, "the torch mode was not restored")
            assertEquals(2f, restoredFake.appliedZoomRatio, "the zoom was not restored")
        } finally {
            second.close()
        }

        val userStates = saved.values.flatten().mapNotNull { value ->
            value?.let { CameraUserStateSaver.restore(it) }
        }
        assertEquals(1, userStates.size, "the saved state holds ${userStates.size} camera user states: $saved")
        assertEquals(2f, userStates.single().zoomRatio, "the saved state does not carry the zoom")
    }

    @Test
    fun restoresTheDeviceTypeAfterProcessDeath() = runTest {
        val saved = savedAfter { holder ->
            assertIs<CameraKResult.Success<*>>(holder.selectCamera(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE))
        }

        val restoredFake = FakeCameraEngine(capabilities())
        val second = Process(this, restoredFake, SaveableStateRegistry(saved) { true })
        try {
            second.frame(this)
            assertIs<CameraKState.Ready>(assertNotNull(second.holder).cameraState.value)

            assertEquals(ULTRA_WIDE_ID, restoredFake.appliedCameraId, "the device type was not restored")
        } finally {
            second.close()
        }
    }

    @Test
    fun aDefaultLensIsNotRestoredAsAnExplicitLens() = runTest {
        val noLens = CameraConfiguration {}

        val untouched = savedAfter(noLens) { }
        assertNull(restoredConfig(untouched, noLens).cameraLens, "the platform default was restored as a lens")

        val picked = savedAfter(noLens) { holder ->
            assertIs<CameraKResult.Success<*>>(holder.selectCamera(CameraLens.FRONT))
        }
        assertEquals(CameraLens.FRONT, restoredConfig(picked, noLens).cameraLens, "the picked lens was not restored")
    }

    // What one process saves after [choose] ran on its holder, ready, over [config].
    private suspend fun TestScope.savedAfter(
        config: CameraConfiguration = CameraConfiguration(),
        choose: suspend (CameraKStateHolder) -> Unit,
    ): Map<String, List<Any?>> {
        val process = Process(this, FakeCameraEngine(capabilities()), SaveableStateRegistry(null) { true }, config)
        try {
            process.frame(this)
            val holder = assertNotNull(process.holder, "nothing was remembered")
            assertIs<CameraKState.Ready>(holder.cameraState.value)
            choose(holder)
            process.frame(this)
            process.frame(this)
            return process.registry.performSave()
        } finally {
            process.close()
        }
    }

    // The configuration a new process over [saved] builds its engine with.
    private fun TestScope.restoredConfig(
        saved: Map<String, List<Any?>>,
        config: CameraConfiguration,
    ): CameraConfiguration {
        val process = Process(this, FakeCameraEngine(capabilities()), SaveableStateRegistry(saved) { true }, config)
        try {
            process.frame(this)
            return assertNotNull(process.engineConfig, "no engine was built")
        } finally {
            process.close()
        }
    }

    private companion object {
        const val ULTRA_WIDE_ID = "back-ultra-wide"
    }
}
