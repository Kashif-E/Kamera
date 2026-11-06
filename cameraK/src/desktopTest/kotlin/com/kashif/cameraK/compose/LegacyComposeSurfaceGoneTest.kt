package com.kashif.cameraK.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `:cameraK`'s 1.x surface that 2.0 removes (design §10.4, w9 G4) is gone: the 1.x permission API,
 * `LocalCameraKStateHolder`, the controller-taking `CameraPreviewView` and the default 1.x screens.
 * Composables compile to static methods of file facades, so the test reads every class `:cameraK`
 * declares in its packages.
 */
class LegacyComposeSurfaceGoneTest {
    @Test
    fun removedSymbolsAreAbsent() {
        val classes = PACKAGES.flatMap(::classesIn)
        assertTrue(classes.isNotEmpty(), "no :cameraK class found to check")

        val present = classes.filter { it.name == "com.kashif.cameraK.permissions.Permissions" }
            .map { "class ${it.name}" } +
            classes.flatMap { type ->
                type.declaredMethods
                    .filter { it.name in REMOVED_FUNCTIONS }
                    .map { "${type.name}.${it.name}" }
            }

        assertTrue(present.isEmpty(), "1.x symbols still present:\n${present.distinct().joinToString("\n")}")
    }

    // The top-level classes compiled from the main sources on this test's classpath in [packageName].
    private fun classesIn(packageName: String): List<Class<*>> =
        javaClass.classLoader.getResources(packageName.replace('.', '/')).toList()
            .filter { it.protocol == "file" && "/main" in it.path }
            .flatMap { url -> File(url.toURI()).listFiles().orEmpty().toList() }
            .filter { it.name.endsWith(".class") && '$' !in it.name }
            .map { Class.forName("$packageName.${it.name.removeSuffix(".class")}", false, javaClass.classLoader) }

    private companion object {
        val PACKAGES = listOf("com.kashif.cameraK.compose", "com.kashif.cameraK.permissions")

        val REMOVED_FUNCTIONS = setOf(
            "providePermissions",
            "getLocalCameraKStateHolder",
            "CameraPreviewView",
            "DefaultLoadingScreen",
            "DefaultErrorScreen",
            "RequestCameraPermission",
            "RequestStoragePermission",
            "RequestMicrophonePermission",
        )
    }
}
