@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A recording streams from the lens the engine reports. On a phone whose back ultra-wide is a
 * physical lens inside a logical camera (the Pixel 7's), the engine pins it on every use case with
 * Camera2Interop; a VideoCapture bound without the pin records from the logical camera's default
 * lens while the preview shows the ultra-wide. Run on a Pixel 7:
 *
 * ```
 * ./gradlew :cameraK-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.kashif.cameraK.engine.PhysicalLensRecordingDeviceTest
 * ```
 */
internal class PhysicalLensRecordingDeviceTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val driver = DeviceEngineDriver()

    @Test
    fun aRecordingOnTheUltraWideStreamsFromTheUltraWide() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration())
        driver.settle()
        val ultraWide = DesiredHardware(
            selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE),
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            zoomRatio = 1f,
            targetOrientation = null,
            exposureCompensationIndex = 0,
        )
        assertEquals(CameraKResult.Success(Unit), engine.start(ultraWide))
        driver.settle()
        val lens = assertNotNull(engine.hardware.value.camera, "the engine reports no camera").id
        assertEquals(lens, driver.boundSettings().cameraId, "the camera the preview streams from")

        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.path
        val started = engine.startRecording(
            VideoConfiguration {
                enableAudio = false
                outputDirectory = directory
            },
        )
        val path = assertIs<CameraKResult.Success<String>>(started, "the recording did not start: $started").value
        try {
            driver.settle()
            assertEquals(lens, driver.boundSettings().cameraId, "the camera every bound use case streams from")
        } finally {
            engine.stopRecording()
            driver.settle()
            File(path).delete()
            engine.close()
        }
    }
}
