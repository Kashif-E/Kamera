package com.kashif.cameraK.state

import kotlin.reflect.KVisibility
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 1.x surface design §10.4 removes in 2.0 is gone from core's common and desktop code: no shim
 * keeps it working, and the only 1.x declarations left are the ERROR-level hints. Read by name, so
 * the test compiles with or without them.
 */
class LegacySurfaceGoneTest {
    @Test
    fun removedSymbolsAreAbsent() {
        val present = mutableListOf<String>()

        listOf(
            "com.kashif.cameraK.controller.CameraController",
            "com.kashif.cameraK.engine.LegacyControllerEngine",
            "com.kashif.cameraK.engine.LegacyControllerAccess",
            "com.kashif.cameraK.engine.LegacySession",
            "com.kashif.cameraK.engine.DirectLegacySession",
            "com.kashif.cameraK.result.ImageCaptureResult\$Success",
            "com.kashif.cameraK.enums.Rotation",
            "com.kashif.cameraK.utils.InvalidConfigurationException",
            "com.kashif.cameraK.controller.ImagePanel",
            "com.kashif.cameraK.controller.ImageCaptureManager",
        ).filter(::declared).forEach { present += "class $it" }

        REMOVED_EVENTS.map { "com.kashif.cameraK.state.CameraKEvent\$$it" }
            .filter(::declared)
            .forEach { present += "event $it" }

        val ready = Class.forName("com.kashif.cameraK.state.CameraKState\$Ready")
        if (ready.methods.any { it.name == "getController" }) present += "CameraKState.Ready.controller"

        val holder = CameraKStateHolder::class.java
        holder.constructors
            .filter { constructor -> constructor.parameterTypes.any { it.name == "kotlinx.coroutines.CoroutineScope" } }
            .forEach { present += "the holder's 1.x constructor $it" }
        holder.methods
            .filter { it.name in REMOVED_HOLDER_MEMBERS }
            .forEach { present += "CameraKStateHolder.${it.name}" }

        CameraUIState::class.java.methods
            .filter { it.name in REMOVED_UI_STATE_GETTERS }
            .forEach { present += "CameraUIState.${it.name}" }

        CameraKPlugin::class.java.methods
            .filter { method -> method.name == "onAttach" && method.parameterTypes.contains(holder) }
            .forEach { present += "CameraKPlugin.onAttach(CameraKStateHolder)" }

        Class.forName("com.kashif.cameraK.capabilities.CameraCapabilities").declaredConstructors
            .filter { it.parameterCount == 1 && it.parameterTypes.single() == List::class.java }
            .forEach { present += "CameraCapabilities' one-argument constructor" }

        val grabber = Class.forName("com.kashif.cameraK.controller.CameraGrabber")
        grabber.methods
            .filter { method -> method.parameterTypes.any { it.name == "org.bytedeco.javacv.FrameGrabber" } }
            .forEach { present += "CameraGrabber.${it.name}, which takes the 1.x customGrabber" }
        // CameraGrabber stays public only so a 1.x call of the grabCurrentFrame hint resolves (design §10.4).
        grabber.kotlin.constructors
            .filter { it.visibility == KVisibility.PUBLIC }
            .forEach { present += "CameraGrabber's public constructor $it" }
        grabber.kotlin.members
            .filter { it.visibility == KVisibility.PUBLIC && it.name !in KEPT_GRABBER_MEMBERS }
            .forEach { present += "CameraGrabber.${it.name}" }

        // The desktop converter's 1.x convert(Frame) and its two-frame hold; the grab loop is its only user.
        val converter = Class.forName("com.kashif.cameraK.controller.FrameConverter")
        if (converter.kotlin.visibility == KVisibility.PUBLIC) present += "FrameConverter is public"
        converter.declaredMethods
            .filter { method ->
                method.name == "convert" &&
                    method.parameterTypes.any { it.name == "org.bytedeco.javacv.Frame" }
            }
            .forEach { present += "FrameConverter.convert(Frame)" }
        converter.declaredFields
            .filter { it.name == "legacyHeld" }
            .forEach { present += "FrameConverter.legacyHeld" }

        // Only internal declarations of core's desktop code use it (design §12.4 Z1).
        if (Class.forName("com.kashif.cameraK.controller.OperatingSystem").kotlin.visibility == KVisibility.PUBLIC) {
            present += "OperatingSystem is public"
        }

        assertTrue(present.isEmpty(), "1.x symbols still present:\n${present.joinToString("\n")}")
    }

    private fun declared(name: String): Boolean = try {
        Class.forName(name, false, javaClass.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private companion object {
        val REMOVED_EVENTS = listOf(
            "None",
            "PermissionDenied",
            "QRCodeScanned",
            "TextRecognized",
            "ImageCaptured",
            "CaptureFailed",
            "RecordingStarted",
            "RecordingStopped",
            "RecordingFailed",
            "RecordingMaxDurationReached",
        )

        val REMOVED_HOLDER_MEMBERS = setOf(
            "initialize",
            "getController",
            "getReadyCameraController",
            "emitEvent",
            "getPluginScope",
            "updateUIState",
        )

        // Kotlin reflection lists Any's members with the class's own.
        val KEPT_GRABBER_MEMBERS = setOf("grabCurrentFrame", "equals", "hashCode", "toString")

        val REMOVED_UI_STATE_GETTERS = setOf(
            "getLastError",
            "getZoomLevel",
            "getMaxZoom",
            "isRecording",
            "isPaused",
            "getRecordingDurationMs",
            "getImageFormat",
            "getQualityPrioritization",
        )
    }
}
