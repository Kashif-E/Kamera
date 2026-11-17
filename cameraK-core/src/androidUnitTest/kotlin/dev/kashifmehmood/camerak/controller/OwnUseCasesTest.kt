@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform
import dev.kashifmehmood.camerak.engine.open
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ProcessCameraProvider is one per process. Whatever else the host app has bound to it (its own
 * scanner, a second CameraK screen) belongs to someone else, and nothing this engine does may
 * unbind it.
 */
@RunWith(RobolectricTestRunner::class)
class OwnUseCasesTest {
    private val platform = FakeAndroidPlatform()

    // Another CameraX consumer in the same process, bound before this engine opens the camera.
    private val hostPreview = Preview.Builder().build().also {
        platform.backend.binding.bindToLifecycle(ResumedLifecycleOwner(), CameraSelector.DEFAULT_BACK_CAMERA, it)
    }

    @Test
    fun bindAndRebind_leaveAnotherOwnersUseCasesBound() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        assertTrue(hostPreview in platform.backend.binding.bound, "binding the camera unbound the host's own preview")

        // A lens switch rebinds this engine's use cases.
        val switch = async { engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(switch.await(), "the lens switch")

        assertTrue(hostPreview in platform.backend.binding.bound, "rebinding the camera unbound the host's own preview")
        engine.closeAndJoin()
    }

    @Test
    fun close_unbindsOnlyThisEnginesUseCases() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        engine.close()
        platform.settle(testScheduler)

        assertEquals(
            setOf(hostPreview),
            platform.backend.binding.bound.toSet(),
            "close must unbind exactly its own use cases",
        )
    }
}
