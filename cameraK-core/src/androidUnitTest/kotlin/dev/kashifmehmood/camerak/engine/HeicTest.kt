@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import android.os.Looper
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.error.CameraFeature
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Android cannot write HEIC, and `start` never accepts a format it would ignore (§6.1): a HEIC
 * configuration fails the start with `Unsupported(IMAGE_FORMAT)` before anything is bound.
 */
@RunWith(RobolectricTestRunner::class)
class HeicTest {
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = hostScope.cancel()

    @Test
    fun heicConfiguredStartIsUnsupportedImageFormat() = runTest {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(testScheduler, CameraConfiguration { imageFormat = ImageFormat.HEIC })
        platform.settle(testScheduler)

        val result = engine.start(FakeAndroidPlatform.desired)

        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraFeature.IMAGE_FORMAT, error.feature)
        val published = assertIs<CameraKError.Unsupported>(assertIs<EngineState.Failed>(engine.state.value).error)
        assertEquals(CameraFeature.IMAGE_FORMAT, published.feature)
        assertEquals(0, platform.backend.binding.bindCalls, "a HEIC start bound the camera")
        engine.closeAndJoin()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}
