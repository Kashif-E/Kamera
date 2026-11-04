@file:OptIn(CameraKEngineApi::class, CameraKPlatformApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.desktopEngine
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.platform.DesktopGrabberFactory
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A platform context's [DesktopGrabberFactory] replaces only how a device is opened (§2.4 desktop):
 * the engine still enumerates, every open calls the factory with the selected device's id, and
 * with no local device a factory engine offers the one default entry, so a machine without a
 * camera can open a stream. Without a factory and with no device there is no camera. The factory's
 * grabber is started as returned: CameraK sets no input format on it, even on macOS, where the 1.x
 * `customGrabber` had its format overwritten with AVFoundation's.
 */
class GrabberFactoryTest {
    private val requested = CopyOnWriteArrayList<String>()

    @Test
    fun platformContextFactoryIsUsedForTheSelectedId() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = factoryEngine(rig, LocalDevices(webcam("usb-a"), webcam("usb-b")))
        try {
            runCurrent()
            assertEquals(CameraKResult.Success(Unit), start(rig, sut, CameraSelection.ById("usb-b")))

            assertEquals(listOf("usb-b"), requested.toList(), "the ids the factory was asked for")
            assertEquals(1, rig.camera.startCount, "the factory's grabber was not the one started")
            assertEquals("usb-b", sut.hardware.value.camera?.id)
        } finally {
            sut.close()
        }
    }

    @Test
    fun factoryEngineStillEnumeratesAndPassesTheEnumeratedId() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val local = LocalDevices(webcam("usb-a"), webcam("usb-b"))
        val sut = factoryEngine(rig, local)
        try {
            runCurrent()

            assertEquals(1, local.lists, "enumerations")
            assertEquals(listOf("usb-a", "usb-b"), sut.capabilities.value.allLenses.map { it.id })
            assertEquals(CameraKResult.Success(Unit), start(rig, sut, BACK))
            assertEquals(listOf("usb-a"), requested.toList(), "the default device's id")
            assertEquals(0, local.opens, "the engine opened a local device past the factory")
        } finally {
            sut.close()
        }
    }

    @Test
    fun factoryEngineWithNoLocalDeviceOffersTheDefaultEntry() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = factoryEngine(rig, LocalDevices())
        try {
            runCurrent()

            assertEquals(listOf(desktopLensInfo()), sut.capabilities.value.allLenses)
            assertEquals(CameraKResult.Success(Unit), start(rig, sut, BACK))
            assertEquals(listOf(desktopLensInfo().id), requested.toList())
        } finally {
            sut.close()
        }
    }

    @Test
    fun noFactoryAndNoDeviceIsNoCamera() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val local = LocalDevices()
        val sut = desktopEngine(CameraConfiguration(), CameraKPlatformContext(), rig.environment(), devices = local)
        try {
            runCurrent()

            assertEquals(emptyList(), sut.capabilities.value.allLenses)
            val result = start(rig, sut, BACK)
            assertIs<CameraKError.NoCamera>(assertIs<CameraKResult.Failure>(result).error)
            assertIs<EngineState.Failed>(sut.state.value)
            assertEquals(0, local.opens, "a camera was opened with none enumerated")
        } finally {
            sut.close()
        }
    }

    @Test
    fun aFactoryFfmpegGrabberIsStartedAsReturnedOnMac() = runTest {
        val stream = FormatRecordingGrabber("rtsp://camera.test/stream")
        val source = FactoryGrabberSource(DesktopGrabberFactory { stream })

        source.open(webcam("usb-a"), own = {}, release = {}, os = OperatingSystem.MAC)

        assertEquals(1, stream.starts, "starts")
        assertEquals(null, stream.formatAtStart, "the input format the factory's grabber was started with")
    }

    @Test
    fun aCustomFfmpegGrabberStillReadsMacCamerasThroughAvfoundation() = runTest {
        val grabber = FormatRecordingGrabber("0")

        CustomGrabberSource(grabber).open(desktopLensInfo(), own = {}, release = {}, os = OperatingSystem.MAC)

        assertEquals(1, grabber.starts, "starts")
        assertEquals("avfoundation", grabber.formatAtStart, "the 1.x customGrabber's format on macOS")
    }

    private fun factoryEngine(rig: DesktopEngineRig, local: LocalDevices) = desktopEngine(
        CameraConfiguration(),
        CameraKPlatformContext(
            DesktopGrabberFactory { id ->
                requested += id
                rig.camera
            },
        ),
        rig.environment(),
        devices = local,
    )

    private suspend fun TestScope.start(
        rig: DesktopEngineRig,
        sut: DesktopCameraEngine,
        selection: CameraSelection,
    ): CameraKResult<Unit> {
        val start = async {
            sut.start(DesiredHardware(selection, FlashMode.OFF, TorchMode.OFF, 1f, null))
        }
        rig.settle()
        return start.await()
    }

    /** The machine's own cameras: listed, and never opened when a factory is set. */
    private class LocalDevices(vararg lenses: LensInfo) : DesktopDevices {
        private val lenses = lenses.toList()

        @Volatile var lists = 0

        @Volatile var opens = 0

        override fun list(): List<LensInfo> {
            lists++
            return lenses
        }

        override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber {
            opens++
            error("the engine opened the local device '$id'")
        }
    }

    /** An FFmpeg grabber that records the input format it is started with and touches no native. */
    private class FormatRecordingGrabber(input: String) : FFmpegFrameGrabber(input) {
        @Volatile var starts = 0

        @Volatile var formatAtStart: String? = null

        override fun start() {
            starts++
            formatAtStart = format
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private companion object {
        val BACK = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT)

        fun webcam(id: String) =
            LensInfo(id, CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)
    }
}
