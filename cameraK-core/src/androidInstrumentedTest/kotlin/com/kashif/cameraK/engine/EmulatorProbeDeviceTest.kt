@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.Manifest
import android.util.Log
import androidx.test.rule.GrantPermissionRule
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * CI-only probe, not a test of behaviour: logs (tag CameraKProbe) what the emulator does when the front
 * telephoto, a physical lens inside logical camera 1, is pinned in each capture mode, and how PNG
 * captures in SPEED mode end. ci/full-test's android-probe job runs it with the whole logcat saved.
 */
internal class EmulatorProbeDeviceTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val driver = DeviceEngineDriver()

    private fun desired(type: CameraDeviceType) = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.FRONT, type),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
        exposureCompensationIndex = 0,
    )

    private suspend fun probe(label: String, type: CameraDeviceType, config: CameraConfiguration, captures: Int) {
        Log.i(TAG, "BEGIN $label")
        val engine = driver.createEngine(config)
        try {
            driver.settle()
            val started = engine.start(desired(type))
            driver.settle()
            Log.i(TAG, "$label start=$started state=${engine.state.value} camera=${engine.hardware.value.camera?.id}")
            if (started !is CameraKResult.Success) return
            repeat(captures) { i ->
                val mark = TimeSource.Monotonic.markNow()
                // Real time: runTest's virtual clock would end the wait at once while CameraX works on its own threads.
                val result = withContext(Dispatchers.Default) { withTimeoutOrNull(20.seconds) { engine.capture() } }
                Log.i(TAG, "$label capture#$i after ${mark.elapsedNow().inWholeMilliseconds} ms: $result")
                (result as? CameraKResult.Success)?.value?.path?.let { File(it).delete() }
            }
        } finally {
            engine.closeAndJoin()
            Log.i(TAG, "END $label")
        }
    }

    @Test
    fun probe() = runTest(timeout = 600.seconds) {
        for (quality in QualityPrioritization.entries) {
            probe(
                "pin-telephoto/$quality",
                CameraDeviceType.TELEPHOTO,
                CameraConfiguration { qualityPrioritization = quality },
                captures = 1,
            )
        }
        for (type in listOf(CameraDeviceType.WIDE_ANGLE, CameraDeviceType.TELEPHOTO)) {
            for (format in listOf(ImageFormat.PNG, ImageFormat.JPEG)) {
                probe(
                    "speed/$type/$format",
                    type,
                    CameraConfiguration {
                        qualityPrioritization = QualityPrioritization.SPEED
                        imageFormat = format
                    },
                    captures = 12,
                )
            }
        }
    }

    private companion object {
        const val TAG = "CameraKProbe"
    }
}
