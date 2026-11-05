@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A configuration with no lens asks for the platform default camera (design §6.1 "Default lens"):
 * the holder starts the engine on `Default`, on `ByLens` when a lens is set, and on `ById` when a
 * camera id is set, which wins over the lens.
 */
class DefaultLensTest {
    // The fake, recording the hardware each start asks for.
    private class RecordingEngine(private val fake: FakeCameraEngine) : CameraEngine by fake {
        val starts = mutableListOf<DesiredHardware>()

        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
            starts += desired
            return fake.start(desired)
        }
    }

    @Test
    fun aConfigurationWithoutALensStartsTheEngineOnDefault() = runTest {
        assertEquals(CameraSelection.Default(CameraDeviceType.DEFAULT), startedOn(CameraConfiguration {}))
        assertEquals(
            CameraSelection.Default(CameraDeviceType.ULTRA_WIDE),
            startedOn(CameraConfiguration { cameraDeviceType = CameraDeviceType.ULTRA_WIDE }),
        )
        assertEquals(
            CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT),
            startedOn(CameraConfiguration { cameraLens = CameraLens.FRONT }),
        )
        assertEquals(
            CameraSelection.ById(FakeCapabilities.FRONT_ID),
            startedOn(
                CameraConfiguration {
                    cameraLens = CameraLens.BACK
                    cameraId = FakeCapabilities.FRONT_ID
                },
            ),
        )
    }

    private fun TestScope.startedOn(config: CameraConfiguration): CameraSelection {
        val engine = RecordingEngine(FakeCameraEngine())
        val holder = CameraKStateHolder(config, { _, _ -> engine }, emptyList(), cameraKTestEnvironment())
        holder.start()
        runCurrent()
        holder.close()
        runCurrent()
        return engine.starts.single().selection
    }
}
