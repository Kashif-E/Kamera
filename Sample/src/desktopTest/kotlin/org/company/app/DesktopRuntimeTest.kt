package org.company.app

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * CameraK reads macOS's camera authorization and lists the cameras through JNA, which it declares
 * compile-only. Without JNA at runtime the Sample offered only the default camera and its permission
 * read as not determined for good.
 */
class DesktopRuntimeTest {
    @Test
    fun jnaIsOnTheDesktopRuntimeClasspath() {
        val jna = runCatching { Class.forName("com.sun.jna.Native") }.getOrNull()
        assertNotNull(jna, "net.java.dev.jna:jna is not on the Sample's desktop runtime classpath")
    }
}
