@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.state

import dev.kashifmehmood.camerak.engine.CameraEngine
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.FakeCapabilities
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
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
