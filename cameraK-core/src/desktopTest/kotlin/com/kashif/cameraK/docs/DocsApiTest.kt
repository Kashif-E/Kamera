package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README and the pages under docs/ teach the 2.0 API. The migration guide, which has to name
 * the 1.x symbols and artifact ids it replaces, and docs/superpowers/ (design notes) are not read.
 */
class DocsApiTest {
    private val root = DocsSources.repoRoot

    /** README.MD and every page under docs/ that teaches the current API, by path from the root. */
    private val teachingDocs: List<String> by lazy {
        val docs = File(root, "docs").walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it.startsWith("docs/superpowers/") || it == MIGRATION_GUIDE }
            .sorted()
            .toList()
        listOf(DocsSources.README) + docs
    }

    @Test
    fun pluginCoordinatesAreThe2_0ArtifactIds() {
        val stale = teachingDocs.flatMap { doc ->
            DocsSources.lines(doc).withIndex().flatMap { (index, line) ->
                COORDINATE.findAll(line).map { it.groupValues[1] }
                    .filterNot { it in ARTIFACT_IDS }
                    .map { "$doc:${index + 1}: $it" }
            }
        }
        assertTrue(stale.isEmpty(), "artifact ids that 2.0 does not publish:\n${stale.joinToString("\n")}")

        val readmeIds = COORDINATE.findAll(File(root, DocsSources.README).readText()).map { it.groupValues[1] }.toSet()
        val missing = PLUGIN_ARTIFACT_IDS - readmeIds
        assertTrue(missing.isEmpty(), "${DocsSources.README} does not install $missing")
    }

    /**
     * No page teaches a symbol 2.0 removed (§10.4 with the C1' and Z1 removals, w10 G2) or a 1.x
     * call that 2.0 keeps only as an ERROR-level hint. Each finding names what replaces the symbol.
     */
    @Test
    fun docsReferenceNoRemovedSymbol() {
        val findings = teachingDocs.flatMap { doc ->
            val text = File(root, doc).readText()
            REMOVED.flatMap { (pattern, replacement) ->
                pattern.findAll(text).map { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    "$doc:$line: '${match.value.replace(Regex("\\s+"), " ").trim()}' ($replacement)"
                }
            }
        }
        assertTrue(teachingDocs.size > 1, "no page under docs/ was read")
        assertTrue(findings.isEmpty(), "the docs teach removed 1.x API:\n${findings.joinToString("\n")}")
    }

    /**
     * No main source of a published module carries a fenced code block in its KDoc, which no
     * compiler checks. A sample is a function in that module's test sources, which compile with the
     * module's tests, and the KDoc references it with `@sample`; every `@sample` resolves to one.
     */
    @Test
    fun kdocSamplesCompile() {
        val modules = root.listFiles().orEmpty()
            .filter { File(it, "src").isDirectory && publishesAnArtifact(it) }
            .sortedBy { it.name }
        assertTrue(modules.size > 1, "found no published module under $root")
        val findings = modules.flatMap { module ->
            val samples = kotlinSources(module, "Test")
            kotlinSources(module, "Main").flatMap { file ->
                var inFence = false
                file.readLines().withIndex().flatMap { (index, line) ->
                    val at = "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}"
                    val opensFence = KDOC_FENCE.matches(line) && !inFence
                    if (KDOC_FENCE.matches(line)) inFence = !inFence
                    val fence = "$at: a fenced code block in KDoc; move it into a sample function and reference it " +
                        "with @sample"
                    val unresolved = SAMPLE_TAG.findAll(line).map { it.groupValues[1] }
                        .filterNot { declares(samples, it) }
                        .map { "$at: @sample $it is no function in ${module.name}'s test sources" }
                        .toList()
                    listOfNotNull(fence.takeIf { opensFence }) + unresolved
                }
            }
        }
        assertTrue(findings.isEmpty(), "KDoc samples that no compiler checks:\n${findings.joinToString("\n")}")
    }

    private fun publishesAnArtifact(module: File): Boolean = File(module, "gradle.properties").let { properties ->
        properties.isFile && properties.readLines().any { it.startsWith("POM_ARTIFACT_ID=") }
    }

    /** The Kotlin files of [module]'s source sets whose names end in [kind] ("Main" or "Test"). */
    private fun kotlinSources(module: File, kind: String): List<File> = File(module, "src").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.endsWith(kind) }
        .flatMap { set -> File(set, "kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Whether one of [files] declares the function [qualifiedName] at top level of its package. */
    private fun declares(files: List<File>, qualifiedName: String): Boolean {
        val packageName = qualifiedName.substringBeforeLast('.', "")
        val name = qualifiedName.substringAfterLast('.')
        val function = Regex("""(?m)^(?:[@\w().]+\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?${Regex.escape(name)}\s*\(""")
        return files.any { file ->
            val text = file.readText()
            Regex("""(?m)^package\s+([\w.]+)""").find(text)?.groupValues?.get(1) == packageName &&
                function.containsMatchIn(text)
        }
    }

    /**
     * The README and the installation guide install CameraK with the same Gradle block, compared as
     * text. Neither includes the other: GitHub renders the README and runs no include.
     */
    @Test
    fun readmeAndGuideShareOneInstallSnippet() {
        val readme = DocsSources.firstFenceAfter(DocsSources.README, README_INSTALL_HEADING)
        val guide = DocsSources.firstFenceAfter(DocsSources.INSTALLATION_GUIDE, GUIDE_INSTALL_HEADING)
        fun normalized(block: String) = block.lines().map(String::trimEnd).joinToString("\n").trim()
        assertEquals(
            normalized(readme),
            normalized(guide),
            "the install block under '$README_INSTALL_HEADING' in ${DocsSources.README} and under " +
                "'$GUIDE_INSTALL_HEADING' in ${DocsSources.INSTALLATION_GUIDE} differ",
        )
    }

    private companion object {
        const val README_INSTALL_HEADING = "## Installation"
        const val GUIDE_INSTALL_HEADING = "## Step 1: Add Dependencies"

        const val MIGRATION_GUIDE = "docs/migration/2.0.md"

        val KDOC_FENCE = Regex("""^\s*\*\s*```.*""")
        val SAMPLE_TAG = Regex("""@sample\s+([\w.]+)""")

        /** A removed symbol, or a 1.x call that is only a hint, with what replaces it. */
        val REMOVED: List<Pair<Regex, String>> = listOf(
            """\bCameraController\b""" to "CameraKStateHolder",
            """\b(?:CameraControllerBuilder|DesktopCameraControllerBuilder|setGrabber|customGrabber)\b""" to
                "the CameraConfiguration { } DSL; on desktop, CameraKPlatformContext(grabberFactory)",
            """\b(?:takePictureToFile|takePicture|captureImage)\b""" to "holder.capture()",
            """\brememberCameraKState\b""" to "rememberCameraKStateHolder",
            """\bCameraKScreen\(\s*(?:modifier\s*=[^,]*,\s*)?cameraState\b""" to "CameraKScreen(holder)",
            """\b(?:CameraPreviewView|DefaultLoadingScreen|DefaultErrorScreen)\b""" to
                "CameraPreview(holder) and CameraKScreen's loading and error slots",
            """\bshowPreview\b""" to "the holder without CameraPreview",
            """\bLocalCameraKStateHolder\b""" to "pass the holder",
            """\b(?:ImageCaptureResult|VideoCaptureResult)\b""" to
                "CameraKResult<CapturedImage> and CameraKResult<RecordedVideo>",
            """\.exception\b""" to "CameraKError and its cause",
            """\bDirectory\b""" to "MediaSaverConfig's GalleryDestination, or outputDirectory",
            """\bRotation\.""" to "DeviceOrientation and setTargetOrientation",
            """\bInvalidConfigurationException\b""" to "CameraKError.Unsupported",
            """\.Initializing\b|\bis Initializing\b""" to "CameraKState.Idle and Starting",
            """\bCameraKEvent\.None\b""" to "nothing; events are only RecordingEnded and PluginFailed",
            """\b(?:PermissionDenied|QRCodeScanned|TextRecognized|ImageCaptured|CaptureFailed|RecordingStarted|""" +
                """RecordingStopped|RecordingFailed|RecordingMaxDurationReached)\b""" to
                "the plugins' results, holder.captures, recordingState and CameraKEvent.RecordingEnded",
            """\b(?:getController|getReadyCameraController|updateUIState|emitEvent|pluginScope)\b""" to
                "the holder's commands and flows",
            """\.controller\b""" to "the holder's commands",
            """\b(?:toggleFlashMode|toggleTorchMode|toggleCameraLens)\b""" to "cycleFlashMode, toggleTorch, toggleLens",
            """\bshutdown\(\)""" to "close()",
            """\bgetCameraCapabilities\b""" to "capabilities.value",
            """\b(?:lastError|zoomLevel|recordingDurationMs)\b""" to
                "cameraState's Error, holder.zoom, rememberRecordingElapsed",
            """\buiState\.(?:isRecording|isPaused|maxZoom|imageFormat|qualityPrioritization)\b""" to
                "holder.recordingState, holder.zoom and the configuration",
            """\bsetPreferredCameraDeviceType\b""" to "selectCamera",
            """\bonAttach\(\s*\w+\s*:\s*CameraKStateHolder\b""" to "onAttach(context: CameraKPluginContext)",
            """\bCameraConfiguration\(\s*\w+\s*=""" to "the CameraConfiguration { } DSL",
            """\bVideoConfiguration\(\s*\w+\s*=""" to "the VideoConfiguration { } DSL",
            """\bmaxDurationMs\b""" to "maxDuration: Duration?",
            """\b(?:ScannerHandle|RecognitionHandle|AnalyzerHandle)\b""" to "FrameRegistration",
            """\b(?:getQrCodeFlow|textFlow|ocrFlow|getAnalyzerFlow|extractTextFromBitmap|attachToStateHolder)\b""" to
                "the plugin's results and recognize",
            """\b(?:startScanning|stopScanning|startRecognition|stopRecognition|startAnalyzer|stopAnalyzer)\b""" to
                "the plugins' pause and resume",
            """\b(?:ImageSaverPlugin|rememberImageSaverPlugin|ImageSaverConfig|ImageSaveResult)\b""" to
                "MediaSaverPlugin, rememberMediaSaverPlugin, MediaSaverConfig, SavedImage",
            """\bcom\.kashif\.imagesaverplugin\b""" to "com.kashif.mediasaverplugin",
            """\b(?:saveImage|getByteArrayFrom|isAutoSave|customFolderName)\b""" to
                "save(CapturedImage), readBytes, autoSave, folderName",
            """\b(?:VideoRecorderPlugin|rememberVideoRecorderPlugin|videoRecorderPlugin)\b""" to
                "the holder's recording commands",
            """\b(?:grabCurrentFrame|ImagePanel|ImageCaptureManager|MemoryManager|BurstCaptureManager)\b""" to
                "holder.capture() and CameraPreview",
            """\bprovidePermissions\b|\bRequest(?:Camera|Storage|Microphone)Permission\b|\bPermissions\.[a-z]""" to
                "rememberCameraKPermissionState",
            """\bCameraGrabber\b""" to "CameraKPlatformContext(grabberFactory)",
            """\b(?:getActivityOrNull|compressToByteArray|toUIImage|fixOrientation|OperatingSystem)\b""" to
                "nothing; removed in 2.0",
            """\b(?:ImageProxy|UIImage)\.toByteArray\b""" to "nothing; removed in 2.0",
            """\b(?:FrameConverter|legacyHeld)\b""" to "holder.frames.register",
            """\bcom\.kashif\.(?:ocrPlugin|analyzerPlugin)\b""" to "com.kashif.ocrplugin, com.kashif.analyzerplugin",
        ).map { (pattern, replacement) -> Regex(pattern) to replacement }

        val COORDINATE = Regex("""io\.github\.kashif-mehmood-km:([A-Za-z0-9_.-]+)""")

        /** §9.4: the plugins' 2.0 artifact ids. Their 1.x ids publish only relocation POMs. */
        val PLUGIN_ARTIFACT_IDS = setOf("camerak-media-saver", "camerak-qr-scanner", "camerak-ocr", "camerak-analyzer")

        val ARTIFACT_IDS = setOf("camerak", "camerak-core", "camerak-testing", "camerak-bom") + PLUGIN_ARTIFACT_IDS
    }
}
