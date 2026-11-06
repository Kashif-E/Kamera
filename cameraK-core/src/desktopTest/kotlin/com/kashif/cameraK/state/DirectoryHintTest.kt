package com.kashif.cameraK.state

import java.io.File
import java.lang.reflect.Executable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `Directory` is read nowhere in 2.0 (design §10.4): it stays only as the type of the 1.x
 * named-argument factory hint's `directory` parameter, so it is an ERROR-level deprecated enum with
 * no ReplaceWith of its own, and nothing else in core takes one.
 */
class DirectoryHintTest {
    @Test
    fun directoryIsErrorDeprecatedAndOnlyTheFactoryHintTakesIt() {
        val directory = Class.forName("com.kashif.cameraK.enums.Directory")
        val deprecation = assertNotNull(directory.getAnnotation(Deprecated::class.java), "Directory is not deprecated")
        assertEquals(DeprecationLevel.ERROR, deprecation.level)
        assertEquals("", deprecation.replaceWith.expression, "Directory has no replacement of its own")

        val unreadable = mutableListOf<String>()
        val takers = coreClasses().flatMap { type ->
            val executables = try {
                type.declaredMethods.toList<Executable>() + type.declaredConstructors
            } catch (e: LinkageError) {
                unreadable += "${type.name}: $e"
                emptyList()
            }
            executables
                // A function's synthetic `$default` stub is that function.
                .filter { executable -> !executable.isSynthetic && directory in executable.parameterTypes }
                .map { "${type.name}.${it.name}" }
        }.distinct()
        assertEquals(emptyList(), unreadable, "classes whose signatures could not be read")

        assertEquals(
            listOf("com.kashif.cameraK.state.CameraConfigurationKt.CameraConfiguration"),
            takers,
            "what takes a Directory",
        )
        val factory = Class.forName("com.kashif.cameraK.state.CameraConfigurationKt").methods
            .single { it.name == "CameraConfiguration" && directory in it.parameterTypes }
        assertTrue(
            factory.getAnnotation(Deprecated::class.java)?.level == DeprecationLevel.ERROR,
            "the factory that takes a Directory is the ERROR-level hint",
        )
    }

    // Every class compiled from core's common and desktop main sources.
    private fun coreClasses(): List<Class<*>> {
        val roots = javaClass.classLoader.getResources("com/kashif/cameraK").toList()
            .filter { it.protocol == "file" && "/main" in it.path }
            .map { File(it.toURI()).parentFile.parentFile.parentFile }
        assertTrue(roots.isNotEmpty(), "core's main classes are not on the classpath as a directory")
        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                .filter { it.startsWith("com.kashif.cameraK.") }
                .map { Class.forName(it, false, javaClass.classLoader) }
                .toList()
        }
    }
}
