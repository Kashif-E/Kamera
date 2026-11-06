@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * With the 1.x controller gone, `Ready` carries nothing (design §13.1): it is a data object, so every
 * Ready any holder publishes is the same value, as Idle and Starting are.
 */
class ReadyIsDataObjectTest {
    private fun TestScope.readyHolder(): CameraKStateHolder =
        CameraKStateHolder(CameraConfiguration(), { _, _ -> FakeCameraEngine() }, emptyList(), cameraKTestEnvironment())
            .also { it.start() }

    @Test
    fun allReadyValuesAreEqual() = runTest {
        val ready = CameraKState.Ready::class
        val instance = assertNotNull(ready.objectInstance, "Ready is not an object")
        assertTrue(ready.isData, "Ready is not a data object")
        assertEquals("Ready", instance.toString())

        val first = readyHolder()
        val second = readyHolder()
        runCurrent()

        assertSame(instance, first.cameraState.value, "the first holder's Ready")
        assertSame(instance, second.cameraState.value, "the second holder's Ready")
        first.close()
        second.close()
    }
}
