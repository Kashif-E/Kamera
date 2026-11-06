package com.kashif.analyzerplugin

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plugin's 1.x surface that its W8 slice removed with no hint (design §10.4) stays gone: the
 * handle, the 1.x flows and start and stop members, and anything that takes the deleted
 * CameraController or a caller's CoroutineScope. A characterization test: the mutation it catches
 * is putting one of them back.
 */
class LegacyPluginSurfaceGoneTest {
    @Test
    fun removedSymbolsAreAbsent() {
        val classes = classesIn("com.kashif.analyzerplugin")
        assertTrue(
            classes.any {
                it.name == "com.kashif.analyzerplugin.AnalyzerPlugin"
            },
            "no plugin class found in $classes",
        )

        val present = classes.filter { it.simpleName == "AnalyzerHandle" }.map { "class ${it.name}" } +
            classes.flatMap { type ->
                type.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                    .filter { method ->
                        method.name in REMOVED_MEMBERS ||
                            method.parameterTypes.any { it.name in REMOVED_PARAMETER_TYPES }
                    }
                    .map { "${type.simpleName}.${it.name}" }
            } +
            classes.flatMap { type ->
                type.declaredConstructors
                    .filter { Modifier.isPublic(it.modifiers) }
                    .filter { constructor -> constructor.parameterTypes.any { it.name in REMOVED_PARAMETER_TYPES } }
                    .map { "a ${type.simpleName} constructor taking ${it.parameterTypes.map { p -> p.simpleName }}" }
            }

        assertTrue(present.isEmpty(), "1.x plugin symbols still present:\n${present.distinct().joinToString("\n")}")
    }

    // The public top-level and nested classes compiled from this module's main sources in [packageName].
    private fun classesIn(packageName: String): List<Class<*>> =
        javaClass.classLoader.getResources(packageName.replace('.', '/')).toList()
            .filter { it.protocol == "file" && "/main" in it.path }
            .flatMap { url -> File(url.toURI()).listFiles().orEmpty().toList() }
            .filter { it.name.endsWith(".class") }
            .map { Class.forName("$packageName.${it.name.removeSuffix(".class")}", false, javaClass.classLoader) }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private companion object {
        val REMOVED_MEMBERS = setOf(
            "getAnalyzerFlow",
            "startAnalyzer",
            "stopAnalyzer",
            "attachToStateHolder",
        )

        val REMOVED_PARAMETER_TYPES = setOf(
            "com.kashif.cameraK.controller.CameraController",
            "kotlinx.coroutines.CoroutineScope",
        )
    }
}
