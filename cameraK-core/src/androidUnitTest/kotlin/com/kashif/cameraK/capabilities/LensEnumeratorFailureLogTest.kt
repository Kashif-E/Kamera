package com.kashif.cameraK.capabilities

import android.hardware.camera2.CameraCharacteristics
import com.kashif.cameraK.utils.CameraKLogger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Each Camera2 read the lens enumeration falls back from is logged with its throwable, so a
 * missing lens can be traced to the failure that dropped it. The message names the operation; the
 * throwable's own message, which may name anything, stays out of it.
 */
@RunWith(RobolectricTestRunner::class)
class LensEnumeratorFailureLogTest {
    private class Logged(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?)

    private val logged = mutableListOf<Logged>()
    private val failure = IllegalStateException("scripted Camera2 failure")

    @Before
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable -> logged += Logged(level, message, throwable) }
    }

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun listingTheCamerasFails_reportsNoneAndLogsTheThrowable() {
        val reader = ScriptedReader(cameraIds = { throw failure })

        assertTrue(LensEnumerator.snapshot(reader).isEmpty())
        assertLoggedOnce()
    }

    @Test
    fun readingOneCameraFails_listsTheOthersAndLogsTheThrowable() {
        val reader = ScriptedReader(
            cameraIds = { arrayOf("0", "1") },
            characteristics = { id -> if (id == "1") throw failure else backCamera() },
        )

        assertEquals(listOf("0"), LensEnumerator.snapshot(reader).map { it.info.id })
        assertLoggedOnce()
    }

    @Test
    fun readingThePhysicalLensesFails_listsTheCameraAsOneLensAndLogsTheThrowable() {
        val reader = ScriptedReader(
            cameraIds = { arrayOf("0") },
            physicalCameraIds = { throw failure },
        )

        val lenses = LensEnumerator.snapshot(reader)

        assertEquals(listOf("0"), lenses.map { it.info.id })
        assertFalse(lenses.single().info.isLogical)
        assertLoggedOnce()
    }

    private fun assertLoggedOnce() {
        val withFailure = logged.filter { it.throwable != null || failure.message!! in it.message }
        assertEquals(1, withFailure.size, "logs of the failure: ${withFailure.map { it.message }}")
        val log = withFailure.single()
        assertSame(failure, log.throwable, "the fallback dropped the throwable: \"${log.message}\"")
        assertEquals(CameraKLogger.Level.WARN, log.level)
        assertFalse(failure.message!! in log.message, "the throwable's message was formatted into the log")
    }

    private class ScriptedReader(
        private val cameraIds: () -> Array<String>,
        private val characteristics: (String) -> CameraCharacteristics = { backCamera() },
        private val physicalCameraIds: (CameraCharacteristics) -> Set<String> = { emptySet() },
    ) : Camera2Reader {
        override fun cameraIds(): Array<String> = cameraIds.invoke()

        override fun characteristics(id: String): CameraCharacteristics = characteristics.invoke(id)

        override fun physicalCameraIds(characteristics: CameraCharacteristics): Set<String> =
            physicalCameraIds.invoke(characteristics)
    }

    private companion object {
        fun backCamera(): CameraCharacteristics {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(4.4f))
            }
            return characteristics
        }
    }
}
