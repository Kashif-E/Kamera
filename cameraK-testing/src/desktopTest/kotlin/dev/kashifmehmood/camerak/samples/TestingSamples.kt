package dev.kashifmehmood.camerak.samples

import dev.kashifmehmood.camerak.state.CameraKState
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertIs

// KDoc samples for camerak-testing, referenced with @sample. They live here so the compiler checks them.

/** In an app's test class, annotated with @Test. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun fakeCameraKStateHolderSample(): TestResult = runTest {
    val fake = FakeCameraEngine().apply { autoOpen = false }
    val holder = fakeCameraKStateHolder(fake = fake)
    holder.start()
    fake.deliverOpen()
    runCurrent()
    assertIs<CameraKState.Ready>(holder.cameraState.value)
}
