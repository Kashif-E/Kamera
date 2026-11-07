@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * The fake as an engine whose exposure compensation has no platform observer, as on Android and iOS:
 * a successful `setExposureCompensation` publishes the index the fake platform now holds before it
 * returns. [exposureReports] lists every exposure index the kit reported through [changeHardware].
 */
internal class ReadBackExposureFakeDriver(capabilities: CameraCapabilities) :
    FakeEngineDriver(capabilities),
    ReadBackExposureDriver {
    val exposureReports = mutableListOf<Int>()

    override fun createEngine(config: CameraConfiguration): CameraEngine =
        ReadsBackExposure(super.createEngine(config) as FakeCameraEngine)

    override fun changeHardware(change: HardwareChange) {
        change.exposureCompensationIndex?.let(exposureReports::add)
        super.changeHardware(change)
    }

    private class ReadsBackExposure(private val fake: FakeCameraEngine) : CameraEngine by fake {
        override suspend fun setExposureCompensation(index: Int): CameraKResult<Unit> {
            val result = fake.setExposureCompensation(index)
            if (result is CameraKResult.Success) fake.reportExposureIndex(fake.appliedExposureCompensationIndex)
            return result
        }
    }
}
