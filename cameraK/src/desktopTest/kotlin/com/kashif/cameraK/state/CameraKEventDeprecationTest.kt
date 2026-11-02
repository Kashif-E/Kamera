package com.kashif.cameraK.state

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Nothing in the library emits [CameraKEvent.None] or [CameraKEvent.PermissionDenied], so an app
 * that waits for either waits forever. Until the next major release removes them, consumers get a
 * compiler warning that says so.
 */
@Suppress("DEPRECATION")
class CameraKEventDeprecationTest {
    private fun deprecationOf(event: KClass<out CameraKEvent>): Deprecated = assertNotNull(
        event.java.getAnnotation(Deprecated::class.java),
        "${event.simpleName} is public, never emitted, and not deprecated",
    )

    @Test
    fun noneIsDeprecatedWithAWarning() {
        val deprecation = deprecationOf(CameraKEvent.None::class)
        assertEquals(DeprecationLevel.WARNING, deprecation.level)
    }

    @Test
    fun permissionDeniedIsDeprecatedWithAWarning() {
        val deprecation = deprecationOf(CameraKEvent.PermissionDenied::class)
        assertEquals(DeprecationLevel.WARNING, deprecation.level)
    }
}
