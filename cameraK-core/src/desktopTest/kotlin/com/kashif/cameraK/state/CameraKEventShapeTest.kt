@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package com.kashif.cameraK.state

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KVisibility
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `CameraKEvent` is open (Decision 8): an abstract class only CameraK can extend. `PluginFailed`
 * carries the failing plugin's id and a `CameraKError`; a thrown exception arrives as `Internal`
 * with the throwable as its cause.
 */
class CameraKEventShapeTest {
    private class ThrowingOnAttach(private val failure: Throwable) : CameraKPlugin {
        override fun onAttach(stateHolder: CameraKStateHolder): Unit = throw failure

        override fun onDetach() = Unit
    }

    private fun TestScope.holderWith(plugins: List<CameraKPlugin> = emptyList()): CameraKStateHolder =
        CameraKStateHolder(CameraConfiguration(), { _, _ -> FakeCameraEngine() }, plugins, cameraKTestEnvironment())

    private fun TestScope.pluginFailures(holder: CameraKStateHolder): List<CameraKEvent.PluginFailed> {
        val received = mutableListOf<CameraKEvent.PluginFailed>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.events.collect { if (it is CameraKEvent.PluginFailed) received += it }
        }
        return received
    }

    @Test
    fun eventIsAnAbstractClassWithAnInternalConstructor() {
        val event = CameraKEvent::class
        assertTrue(event.isAbstract, "CameraKEvent is not abstract")
        assertFalse(event.isSealed, "CameraKEvent is sealed, so a when over it cannot grow in a minor release")
        assertTrue(event.constructors.isNotEmpty(), "CameraKEvent has no constructor to check")
        event.constructors.forEach { constructor ->
            assertEquals(KVisibility.INTERNAL, constructor.visibility, "a CameraKEvent constructor: $constructor")
        }
    }

    @Test
    fun pluginFailedConstructorIsInternalCameraKApi() {
        val constructor = assertNotNull(CameraKEvent.PluginFailed::class.primaryConstructor)
        val names = constructor.annotations.map { it.annotationClass.qualifiedName }
        assertTrue(INTERNAL_API in names, "PluginFailed's constructor annotations: $names")
    }

    @Test
    fun pluginFailedCarriesPluginIdAndCameraKError() = runTest {
        val failure = IllegalStateException("attach failed")
        val holder = holderWith(listOf(ThrowingOnAttach(failure)))
        val failures = pluginFailures(holder)

        holder.start()
        runCurrent()

        val reported = failures.single()
        assertEquals("ThrowingOnAttach", reported.pluginId)
        assertSame(failure, assertIs<CameraKError.Internal>(reported.error).cause)
    }

    @Test
    fun pluginScopeThrowIsInternalWithTheThrowableAsCause() = runTest {
        val holder = holderWith()
        val failures = pluginFailures(holder)
        val failure = IllegalStateException("plugin coroutine failed")

        holder.pluginScope.launch { throw failure }
        runCurrent()

        val reported = failures.single()
        assertEquals("pluginScope", reported.pluginId)
        assertSame(failure, assertIs<CameraKError.Internal>(reported.error).cause)
    }

    private companion object {
        const val INTERNAL_API = "com.kashif.cameraK.utils.InternalCameraKApi"
    }
}
