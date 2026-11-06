package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README and the pages under docs/ teach the 2.0 API. The migration guide, which has to name
 * the 1.x symbols and artifact ids it replaces, and docs/superpowers/ (design notes) are not read
 * as teaching pages; the migration guide and the device checklist are each checked for what they
 * must name.
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

    /**
     * The migration guide names every group of 2.0's removed API (§10.4, with the C1' and Z1
     * removals), each symbol of the group, and the two plugin packages whose imports an app rewrites.
     */
    @Test
    fun migrationGuideCoversEveryRemovedSymbolGroup() {
        val guide = File(root, MIGRATION_GUIDE)
        assertTrue(guide.isFile, "$MIGRATION_GUIDE does not exist")
        val text = guide.readText()
        val missing = REMOVED_GROUPS.mapNotNull { (group, symbols) ->
            symbols.filterNot { Regex("""(?<!\w)${Regex.escape(it)}(?!\w)""").containsMatchIn(text) }
                .takeIf { it.isNotEmpty() }
                ?.let { "$group: ${it.joinToString()}" }
        }
        assertTrue(missing.isEmpty(), "$MIGRATION_GUIDE does not name this removed API:\n${missing.joinToString("\n")}")
        val lines = text.lines()
        val rewrites = IMPORT_REWRITES.filterNot { (old, new) -> lines.any { "`$old.*`" in it && "`$new.*`" in it } }
        assertTrue(
            rewrites.isEmpty(),
            "$MIGRATION_GUIDE has no line rewriting these imports: " +
                rewrites.joinToString { (old, new) -> "$old -> $new" },
        )
    }

    /**
     * The device checklist runs each release gate that verify.sh only compiles: the five
     * Macrobenchmarks and the engine contract's Android device subset, each by a name its sources
     * declare, and no `<B1p command>` placeholder is left. The iPhone contract subset has no Gradle
     * device task; the checklist gives it as steps by hand.
     */
    @Test
    fun deviceChecklistNamesEachReleaseGate() {
        val checklist = File(root, DEVICE_CHECKLIST)
        assertTrue(checklist.isFile, "$DEVICE_CHECKLIST does not exist")
        val text = checklist.readText()
        val benchmarks = File(root, "benchmark/src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val contract = File(root, "cameraK-core/src/engineContract").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        fun declares(files: List<File>, name: String) =
            files.any { Regex("""\bfun\s+${Regex.escape(name)}\s*\(""").containsMatchIn(it.readText()) }

        val findings = buildList {
            RELEASE_GATE_BENCHMARKS.forEach { benchmark ->
                val (className, method) = benchmark.split('.')
                if (benchmark !in text) add("$DEVICE_CHECKLIST does not name the benchmark $benchmark")
                if (benchmarks.none { it.name == "$className.kt" } || !declares(benchmarks, method)) {
                    add("benchmark/src declares no $benchmark, which the release gate runs")
                }
            }
            if (BENCHMARK_RUN !in text) add("$DEVICE_CHECKLIST does not run the benchmarks with $BENCHMARK_RUN")
            if (CONTRACT_RUN !in text) add("$DEVICE_CHECKLIST does not run the contract subset with $CONTRACT_RUN")
            DEVICE_CONTRACT_CASES.forEach { case ->
                if ("$DEVICE_CONTRACT_CLASS#$case" !in text) {
                    add("$DEVICE_CHECKLIST's contract run does not select $DEVICE_CONTRACT_CLASS#$case")
                }
                if (!declares(contract, case)) add("the engine contract kit declares no case $case")
            }
            if (PLACEHOLDER in text) add("$DEVICE_CHECKLIST still holds a $PLACEHOLDER placeholder")
        }
        assertTrue(findings.isEmpty(), "the device checklist misses a release gate:\n${findings.joinToString("\n")}")
    }

    private companion object {
        const val README_INSTALL_HEADING = "## Installation"
        const val GUIDE_INSTALL_HEADING = "## Step 1: Add Dependencies"

        const val MIGRATION_GUIDE = "docs/migration/2.0.md"
        const val DEVICE_CHECKLIST = "docs/device-checklist.md"

        /** §10.4's removed API, group by group, as the migration guide names each symbol. */
        val REMOVED_GROUPS: List<Pair<String, List<String>>> = listOf(
            "C1' removals" to listOf(
                "ImageCaptureResult.Success(ByteArray)",
                "Rotation",
                "LocalCameraKStateHolder",
                "InvalidConfigurationException",
            ),
            "Directory, kept only as a hint's parameter" to listOf("Directory", "GalleryDestination"),
            "the capability constructors" to listOf("CameraCapabilities", "LensInfo"),
            "the removed events" to listOf(
                "CameraKEvent.None",
                "PermissionDenied",
                "QRCodeScanned",
                "TextRecognized",
                "ImageCaptured",
                "CaptureFailed",
                "RecordingStarted",
                "RecordingStopped",
                "RecordingFailed",
                "RecordingMaxDurationReached",
            ),
            "the holder's 1.x constructor and members" to listOf(
                "controllerFactory",
                "initialize()",
                "getController()",
                "getReadyCameraController()",
                "updateUIState()",
                "emitEvent()",
                "pluginScope",
                "Ready.controller",
            ),
            "the holder's commands kept as hints" to listOf(
                "captureImage()",
                "toggleFlashMode()",
                "toggleTorchMode()",
                "toggleCameraLens()",
                "shutdown()",
                "getCameraCapabilities()",
            ),
            "CameraUIState's removed fields" to listOf(
                "lastError",
                "zoomLevel",
                "maxZoom",
                "isRecording",
                "isPaused",
                "recordingDurationMs",
                "imageFormat",
                "qualityPrioritization",
            ),
            "CameraController and the builders" to listOf(
                "CameraController",
                "CameraControllerBuilder",
                "DesktopCameraControllerBuilder.setGrabber",
                "customGrabber",
            ),
            "the plugins' 1.x surface" to listOf(
                "ScannerHandle",
                "RecognitionHandle",
                "AnalyzerHandle",
                "getQrCodeFlow",
                "textFlow",
                "ocrFlow",
                "getAnalyzerFlow",
                "extractTextFromBitmap",
                "attachToStateHolder",
                "startScanning",
                "startRecognition",
                "startAnalyzer",
            ),
            "the saver's 1.x surface" to listOf(
                "ImageSaverConfig",
                "ImageSaveResult",
                "rememberImageSaverPlugin",
                "saveImage",
                "save(ByteArray, name)",
                "readBytes(path)",
                "getByteArrayFrom",
            ),
            "the Wave A deprecations" to listOf("grabCurrentFrame", "ImagePanel", "ImageCaptureManager"),
            "the memory managers (Decision 23)" to listOf("MemoryManager", "BurstCaptureManager"),
            "the 1.x permissions API" to listOf(
                "Permissions",
                "providePermissions()",
                "RequestCameraPermission",
                "RequestStoragePermission",
                "RequestMicrophonePermission",
            ),
            "the video recorder plugin" to listOf("VideoRecorderPlugin", "video_recorder_plugin"),
            "the Z1 removals" to listOf(
                "CameraGrabber",
                "setHorizontalFlip",
                "getActivityOrNull",
                "compressToByteArray",
                "ImageProxy.toByteArray",
                "toUIImage",
                "fixOrientation",
                "UIImage.toByteArray",
                "OperatingSystem",
            ),
            "the other hints" to listOf(
                "rememberCameraKState",
                "CameraKScreen(cameraState",
                "VideoCaptureResult",
                "CameraConfiguration(",
                "VideoConfiguration(",
            ),
        )

        /** The two plugin packages that changed only in letter case, which have no alias (§9.4). */
        val IMPORT_REWRITES = listOf(
            "com.kashif.ocrPlugin" to "com.kashif.ocrplugin",
            "com.kashif.analyzerPlugin" to "com.kashif.analyzerplugin",
        )

        /** §12.4 B1's RELEASE_GATE_BENCHMARKS. */
        val RELEASE_GATE_BENCHMARKS = listOf(
            "BindCountBenchmark.exactlyOneBindToFirstFrame",
            "BindCountBenchmark.zeroRebindsOnAnalyzerRegisterAndUnregister",
            "TimeToFirstFrameBenchmark.againstBareCameraX",
            "SquareCaptureBenchmark.shutterToFileWithAndWithoutTheSquareViewPort",
            "VideoBindBenchmark.videoBindMainThreadTime",
        )
        const val BENCHMARK_RUN = "./gradlew :benchmark:connectedBenchmarkAndroidTest"

        /** §12.4 B1's Android device subset of the engine contract: the cases with no fault hook. */
        const val CONTRACT_RUN = "./gradlew :cameraK-core:connectedDebugAndroidTest"
        const val DEVICE_CONTRACT_CLASS = "com.kashif.cameraK.engine.AndroidCameraEngineDeviceContractTest"
        val DEVICE_CONTRACT_CASES = listOf(
            "stateIsOpenOrInterruptedWhenStartReturnsSuccess",
            "callsAfterCloseReturnClosed",
            "closeIsIdempotentAndReleasesOnce",
            "reportedCameraIsTheOneTheEngineOpenedOn",
            "desiredHardwareReappliedAfterRestartAndCameraSwitch",
        )
        const val PLACEHOLDER = "<B1p command>"

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
