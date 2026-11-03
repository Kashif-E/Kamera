@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * The fake as an engine whose flash has no platform observer, as on Android and iOS: a successful
 * `setFlashMode` publishes a read-back before it returns. [publish] picks the published mode from
 * the mode the fake platform now holds and the modes the bound camera takes; by default it is the
 * platform's own value. [flashReports] lists every flash value the kit reported through
 * [changeHardware].
 */
internal class ReadBackFlashFakeDriver(
    capabilities: CameraCapabilities,
    private val publish: (platform: FlashMode, supported: Set<FlashMode>) -> FlashMode = { platform, _ -> platform },
) : FakeEngineDriver(capabilities),
    ReadBackFlashDriver {
    val flashReports = mutableListOf<FlashMode>()

    override fun createEngine(config: CameraConfiguration): CameraEngine =
        ReadsBackFlash(super.createEngine(config) as FakeCameraEngine, publish)

    override fun changeHardware(change: HardwareChange) {
        change.flashMode?.let(flashReports::add)
        super.changeHardware(change)
    }

    private class ReadsBackFlash(
        private val fake: FakeCameraEngine,
        private val publish: (FlashMode, Set<FlashMode>) -> FlashMode,
    ) : CameraEngine by fake {
        override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
            val result = fake.setFlashMode(mode)
            if (result is CameraKResult.Success) {
                val supported = fake.appliedCameraId?.let(fake.capabilities.value::supportedFlashModes).orEmpty()
                fake.reportFlash(publish(fake.appliedFlashMode, supported))
            }
            return result
        }
    }
}
