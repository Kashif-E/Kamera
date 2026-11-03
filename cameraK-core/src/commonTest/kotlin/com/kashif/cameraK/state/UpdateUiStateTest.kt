@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `updateUIState` cannot write a state derived from the engine. It is an ERROR-level deprecation; a
 * caller compiled against 1.x still links, writes nothing and gets one warning.
 */
class UpdateUiStateTest {
    private val warnings = mutableListOf<String>()

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun updateUIStateWritesNothingAndLogsOnce() = runTest {
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            { _, _ -> FakeCameraEngine() },
            emptyList(),
            cameraKTestEnvironment(),
        )
        holder.start()
        runCurrent()
        val before = holder.uiState.value
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }

        @Suppress("DEPRECATION_ERROR")
        holder.updateUIState { it.copy(zoomLevel = 9f, lastError = "written") }
        @Suppress("DEPRECATION_ERROR")
        holder.updateUIState { it.copy(isCapturing = true) }
        runCurrent()

        assertEquals(before, holder.uiState.value, "updateUIState wrote uiState")
        assertEquals(1, warnings.size, "warnings: $warnings")
        assertTrue(warnings.single().contains("updateUIState"), "the warning does not name the operation: $warnings")
    }
}
