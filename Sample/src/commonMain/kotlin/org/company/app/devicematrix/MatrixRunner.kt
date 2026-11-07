package org.company.app.devicematrix

import com.kashif.analyzerplugin.AnalyzerPlugin
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.state.CameraUIState
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import com.kashif.mediasaverplugin.MediaSaverPlugin
import com.kashif.ocrplugin.OcrPlugin
import com.kashif.qrscannerplugin.QRScannerPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the runner needs from the platform it runs on. */
internal interface MatrixPlatform {
    val platformName: String
    val deviceDescription: String

    /** Where report.json and report.md go: `files/camerak-matrix` on Android, `Documents/camerak-matrix` on iOS. */
    val reportDirectory: String
    val files: MatrixFiles

    /** A production holder, as the Sample builds one. */
    fun newHolder(config: CameraConfiguration, plugins: List<CameraKPlugin> = emptyList()): CameraKStateHolder

    /** A media saver that saves only when asked; null where the platform has none. */
    fun newMediaSaver(): MediaSaverPlugin?

    /** The permission's status as the app last read it; null before it has. */
    fun permission(permission: CameraKPermission): PermissionStatus?

    /** One line under the tag CameraKMatrix. */
    fun log(line: String)
}

/** How long each step may take, and how long the runner records or watches. */
internal class MatrixTiming(
    val ready: Duration = 15.seconds,
    val capture: Duration = 20.seconds,
    val command: Duration = 10.seconds,
    val close: Duration = 10.seconds,
    val readBack: Duration = 2.seconds,
    val permissionWait: Duration = 180.seconds,
    val recordSegment: Duration = 1500.milliseconds,
    val pause: Duration = 1.seconds,
    val analysisWindow: Duration = 3.seconds,
    val pluginWindow: Duration = 5.seconds,
)

internal data class MatrixProgress(
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "starting",
    val counts: Map<Verdict, Int> = emptyMap(),
    val finished: Boolean = false,
)

/**
 * Runs the device matrix against real holders from [platform], one case after another, writing each
 * result to the report as it finishes. [holder] is the holder the preview shows.
 */
internal class MatrixRunner(
    private val platform: MatrixPlatform,
    private val args: MatrixArgs,
    private val timing: MatrixTiming = MatrixTiming(),
) {
    private val _holder = MutableStateFlow<CameraKStateHolder?>(null)
    val holder: StateFlow<CameraKStateHolder?> = _holder.asStateFlow()

    private val _progress = MutableStateFlow(MatrixProgress())
    val progress: StateFlow<MatrixProgress> = _progress.asStateFlow()

    private val files = platform.files
    private val writer = ReportWriter(files, platform.reportDirectory)
    private val clock = TimeSource.Monotonic
    private val counts = mutableMapOf<Verdict, Int>()
    private val sectionCounts = mutableMapOf<MatrixSection, MutableMap<Verdict, Int>>()
    private val recorded = mutableSetOf<Int>()
    private var total = 0
    private lateinit var capabilities: CameraCapabilities

    suspend fun run() {
        val started = clock.markNow()
        log("START ${platform.platformName}: ${platform.deviceDescription}; $args")
        val permissions = CameraKPermission.entries.map { it.name to awaitAnswer(it)?.toString() }
        val header = listOf(
            "platform" to platform.platformName,
            "device" to platform.deviceDescription,
            "offset" to args.offset,
            "sections" to args.sections.map { it.key },
            "limit" to args.limit,
            "permissions" to permissions,
        )
        val probed = probe()
        if (probed == null) {
            writer.begin(args.offset > 0, header + ("error" to "probe: the back camera did not open, no capabilities"))
            finish(started)
            return
        }
        capabilities = probed
        val all = enumerateCases(probed)
        val selected = all.filter { it.index >= args.offset && it.spec.section in args.sections }
            .let { cases -> args.limit?.let { cases.take(it) } ?: cases }
        total = selected.size
        writer.begin(
            resume = args.offset > 0,
            header = header + listOf(
                "matrixCases" to all.size,
                "selectedCases" to selected.size,
                "sectionSizes" to MatrixSection.entries.map { s -> s.key to all.count { it.spec.section == s } },
                "capabilities" to describeCapabilities(probed),
            ),
        )
        log("MATRIX ${all.size} cases; running ${selected.size} from #${args.offset}")
        _progress.value = MatrixProgress(total = total)
        for (group in groups(selected)) runGroup(group)
        finish(started)
    }

    private fun finish(started: TimeSource.Monotonic.ValueTimeMark) {
        val summary = listOf(
            "cases" to recorded.size,
            "elapsedSeconds" to started.elapsedNow().inWholeSeconds,
        ) + Verdict.entries.map { it.label to (counts[it] ?: 0) } +
            (
                "bySection" to sectionCounts.map { (section, byVerdict) ->
                    section.key to byVerdict.map { it.key.label to it.value }
                }
                )
        writer.finish(summary)
        log("DONE " + summary.joinToString(" ") { "${it.first}=${it.second}" })
        _progress.update { it.copy(finished = true, current = "done") }
    }

    // The prompts need a tap on the device; the log says which one is waiting.
    private suspend fun awaitAnswer(permission: CameraKPermission): PermissionStatus? {
        val waiting = clock.markNow()
        var logged = -1L
        while (true) {
            val status = platform.permission(permission)
            if (status != null && status != PermissionStatus.NotDetermined) return status
            val seconds = waiting.elapsedNow().inWholeSeconds
            if (waiting.elapsedNow() >= timing.permissionWait) {
                log("BLOCKED the $permission prompt is unanswered after ${seconds}s (status $status); going on")
                return status
            }
            if (seconds / 10 != logged) {
                logged = seconds / 10
                log("WAITING for the $permission prompt (status $status): it needs a tap on the device")
            }
            _progress.update { it.copy(current = "waiting for the $permission prompt") }
            delay(250)
        }
    }

    private suspend fun probe(): CameraCapabilities? = withHolder(backCamera) { holder ->
        val opened = open(holder)
        log("PROBE ${opened.state} in ${opened.readyMs} ms")
        withTimeoutOrNull(timing.ready) { holder.capabilities.first { it.allLenses.isNotEmpty() } }
    }

    private fun groups(cases: List<MatrixCase>): List<List<MatrixCase>> {
        fun key(spec: CaseSpec): Any = when (spec) {
            is PhotoSpec -> spec.holderKey
            is FeatureSpec -> "camera ${spec.cameraId}"
            is VideoSpec -> spec.lens to spec.aspectRatio
            else -> spec
        }
        val groups = mutableListOf<MutableList<MatrixCase>>()
        for (case in cases) {
            val last = groups.lastOrNull()
            if (last != null && key(last.last().spec) == key(case.spec)) last += case else groups += mutableListOf(case)
        }
        return groups
    }

    private suspend fun runGroup(group: List<MatrixCase>) {
        try {
            when (val spec = group.first().spec) {
                is PhotoSpec -> photoGroup(group)
                is FeatureSpec -> featureGroup(group)
                is VideoSpec -> videoGroup(group)
                is ConfigFlashSpec -> configFlashCase(group.single(), spec)
                is AnalysisSpec -> analysisCase(group.single(), spec)
                is PluginSpec -> pluginCase(group.single(), spec)
                is LifecycleSpec -> lifecycleCase(group.single(), spec)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("EXCEPTION in the group from #${group.first().index}: $e")
            group.filter { it.index !in recorded }.forEach { record(it, Outcome.Threw(e.toString()), Prediction.NONE) }
        }
    }

    // ---- Photo ----

    private suspend fun photoGroup(group: List<MatrixCase>) {
        val key = (group.first().spec as PhotoSpec).holderKey
        val startPrediction = photoStartPrediction(capabilities, key)
        val config = CameraConfiguration {
            cameraLens = key.lens
            cameraDeviceType = key.deviceType
            aspectRatio = key.aspectRatio
            targetResolution = key.targetResolution?.let { Resolution(it.width, it.height) }
            imageFormat = key.imageFormat
            qualityPrioritization = key.quality
            mirrorFrontCamera = key.mirrorFrontCamera
        }
        log("BEGIN #${group.first().index} ${group.first().id} (one holder for ${group.size} cases)")
        withHolder(config) { holder ->
            val opened = open(holder)
            if (!opened.ready) {
                group.forEach { record(it, opened.failure(), startPrediction, listOf("readyMs" to opened.readyMs)) }
                return@withHolder
            }
            val ui = readBack(holder.uiState) { it.cameraId != null }
            val base = listOf("readyMs" to opened.readyMs) + cameraMeasures(ui, key.lens, key.deviceType)
            val cameraChecks = cameraChecks(ui, key.lens, key.deviceType)
            for (case in group) {
                val spec = case.spec as PhotoSpec
                val flash = ui.cameraId?.let { flashPrediction(capabilities, it, spec.flashMode) } ?: Prediction.NONE
                capturePhoto(holder, case, spec, startPrediction + flash, base, cameraChecks)
            }
        }
    }

    private suspend fun capturePhoto(
        holder: CameraKStateHolder,
        case: MatrixCase,
        spec: PhotoSpec,
        predicted: Prediction,
        base: List<Pair<String, Any?>>,
        cameraChecks: List<String>,
    ) {
        log("BEGIN #${case.index} ${case.id}")
        val measures = base.toMutableList()
        val checks = cameraChecks.toMutableList()
        when (val flash = command { holder.setFlashMode(spec.flashMode) }) {
            null -> return record(case, Outcome.TimedOut("setFlashMode"), predicted, measures, checks)
            is CameraKResult.Failure -> return record(
                case,
                Outcome.Refused("setFlashMode", flash.error),
                predicted,
                measures,
                checks,
            )
            is CameraKResult.Success -> Unit
        }
        val flashBack = readBack(holder.uiState) { it.flashMode == spec.flashMode }.flashMode
        measures += "flashReadBack" to flashBack
        if (flashBack != spec.flashMode) checks += "flash read back $flashBack, asked ${spec.flashMode}"
        val capturing = clock.markNow()
        val result = withTimeoutOrNull(timing.capture) { holder.capture() }
        measures += "captureMs" to capturing.elapsedNow().inWholeMilliseconds
        when (result) {
            null -> record(case, Outcome.TimedOut("capture"), predicted, measures, checks)
            is CameraKResult.Failure -> record(
                case,
                Outcome.Refused("capture", result.error),
                predicted,
                measures,
                checks,
            )
            is CameraKResult.Success -> {
                checks += inspectImage(result.value, spec, measures)
                record(case, Outcome.Completed, predicted, measures, checks)
            }
        }
    }

    // Reads the capture's header, deletes the file, and returns the checks that failed.
    private fun inspectImage(image: CapturedImage, spec: PhotoSpec, measures: MeasureList): List<String> {
        val bytes = files.size(image.path)
        val head = if (bytes != null) files.readRange(image.path, 0, HEAD_BYTES) else ByteArray(0)
        files.delete(image.path)
        val magic = sniffImageFormat(head)
        val decoded = imageSize(head)
        val reported = MatrixSize(image.width, image.height)
        measures += listOf(
            "fileBytes" to bytes,
            "magic" to magic,
            "decodedWidth" to decoded?.width,
            "decodedHeight" to decoded?.height,
            "reportedWidth" to image.width,
            "reportedHeight" to image.height,
            "reportedFormat" to image.format,
            "exifOrientation" to jpegExifOrientation(head),
            "matchesTargetResolution" to spec.targetResolution?.let { target -> decoded?.let { it.sameAs(target) } },
        )
        val checks = mutableListOf<String>()
        when (bytes) {
            null -> checks += "no file at ${image.path}"
            0L -> checks += "the file is empty"
        }
        if (magic != spec.imageFormat) checks += "the file is ${magic ?: "no known image"}, asked ${spec.imageFormat}"
        if (image.format != spec.imageFormat) checks += "reported format ${image.format}, asked ${spec.imageFormat}"
        if (decoded == null) {
            if (bytes != null && bytes > 0) checks += "no pixel size in the header"
        } else {
            if (!ratioMatches(decoded.width, decoded.height, spec.aspectRatio)) {
                checks += "ratio of $decoded is not ${spec.aspectRatio}"
            }
            if (!decoded.sameAs(reported)) checks += "the header says $decoded, the capture reported $reported"
        }
        return checks
    }

    // ---- Configuration flash ----

    private suspend fun configFlashCase(case: MatrixCase, spec: ConfigFlashSpec) {
        log("BEGIN #${case.index} ${case.id}")
        withHolder(
            CameraConfiguration {
                cameraLens = spec.lens
                flashMode = spec.flashMode
            },
        ) { holder ->
            val opened = open(holder)
            val cameraId = holder.uiState.value.cameraId ?: capabilities.lenses(spec.lens).firstOrNull()?.id
            val predicted = lensPrediction(capabilities, spec.lens) +
                (cameraId?.let { flashPrediction(capabilities, it, spec.flashMode) } ?: Prediction.NONE)
            val measures = mutableListOf<Pair<String, Any?>>("readyMs" to opened.readyMs, "cameraId" to cameraId)
            if (!opened.ready) return@withHolder record(case, opened.failure(), predicted, measures)
            val back = readBack(holder.uiState) { it.flashMode == spec.flashMode }.flashMode
            measures += "flashReadBack" to back
            val checks = listOfNotNull(
                "flash read back $back, asked ${spec.flashMode}".takeIf { back != spec.flashMode },
            )
            record(case, Outcome.Completed, predicted, measures, checks)
        }
    }

    // ---- Controls, per camera ----

    private suspend fun featureGroup(group: List<MatrixCase>) {
        val first = group.first().spec as FeatureSpec
        log("BEGIN #${group.first().index} ${group.first().id} (one holder for ${group.size} cases)")
        withHolder(CameraConfiguration { cameraId = first.cameraId }) { holder ->
            val opened = open(holder)
            if (!opened.ready) {
                group.forEach { record(it, opened.failure(), Prediction.NONE, listOf("readyMs" to opened.readyMs)) }
                return@withHolder
            }
            val ui = readBack(holder.uiState) { it.cameraId != null }
            val base = listOf("readyMs" to opened.readyMs, "cameraIdReadBack" to ui.cameraId)
            val idChecks = listOfNotNull(
                "camera ${ui.cameraId} opened, asked ${first.cameraId}".takeIf { ui.cameraId != first.cameraId },
            )
            for (case in group) featureCase(holder, case, case.spec as FeatureSpec, base, idChecks)
        }
    }

    private suspend fun featureCase(
        holder: CameraKStateHolder,
        case: MatrixCase,
        spec: FeatureSpec,
        base: List<Pair<String, Any?>>,
        idChecks: List<String>,
    ) {
        log("BEGIN #${case.index} ${case.id}")
        val id = spec.cameraId
        val measures = base.toMutableList()
        val checks = idChecks.toMutableList()
        suspend fun <T> step(
            name: String,
            predicted: Prediction,
            block: suspend () -> CameraKResult<T>,
            then: suspend (T) -> Unit = {},
        ) {
            val running = clock.markNow()
            val result = command(block)
            measures += "${name}Ms" to running.elapsedNow().inWholeMilliseconds
            when (result) {
                null -> record(case, Outcome.TimedOut(name), predicted, measures, checks)
                is CameraKResult.Failure -> record(
                    case,
                    Outcome.Refused(name, result.error),
                    predicted,
                    measures,
                    checks,
                )
                is CameraKResult.Success -> {
                    then(result.value)
                    record(case, Outcome.Completed, predicted, measures, checks)
                }
            }
        }
        when (spec.check) {
            FeatureCheck.ZOOM_MIN, FeatureCheck.ZOOM_MID, FeatureCheck.ZOOM_MAX -> {
                val zoom = holder.zoom.value
                val target = when (spec.check) {
                    FeatureCheck.ZOOM_MIN -> zoom.minRatio
                    FeatureCheck.ZOOM_MAX -> zoom.maxRatio
                    else -> (zoom.minRatio + zoom.maxRatio) / 2
                }
                measures += listOf("minRatio" to zoom.minRatio, "maxRatio" to zoom.maxRatio, "target" to target)
                step("setZoom", Prediction.NONE, { holder.setZoom(target) }) { applied ->
                    val back = readBack(holder.zoom) { near(it.ratio, target) }.ratio
                    measures += listOf("applied" to applied.ratio, "readBack" to back)
                    if (!near(applied.ratio, target)) checks += "applied ${applied.ratio}, asked $target"
                    if (!near(back, target)) checks += "zoom read back $back, asked $target"
                }
            }
            FeatureCheck.TORCH_ON, FeatureCheck.TORCH_OFF -> {
                val mode = if (spec.check == FeatureCheck.TORCH_ON) TorchMode.ON else TorchMode.OFF
                step("setTorchMode", torchPrediction(capabilities, id, mode), { holder.setTorchMode(mode) }) {
                    val back = readBack(holder.uiState) { it.torchMode == mode }.torchMode
                    measures += "torchReadBack" to back
                    if (back != mode) checks += "torch read back $back, asked $mode"
                }
            }
            FeatureCheck.FOCUS_CENTER, FeatureCheck.FOCUS_TOP_LEFT, FeatureCheck.FOCUS_TOP_RIGHT,
            FeatureCheck.FOCUS_BOTTOM_LEFT, FeatureCheck.FOCUS_BOTTOM_RIGHT,
            -> {
                val point = when (spec.check) {
                    FeatureCheck.FOCUS_TOP_LEFT -> NormalizedPoint(0f, 0f)
                    FeatureCheck.FOCUS_TOP_RIGHT -> NormalizedPoint(1f, 0f)
                    FeatureCheck.FOCUS_BOTTOM_LEFT -> NormalizedPoint(0f, 1f)
                    FeatureCheck.FOCUS_BOTTOM_RIGHT -> NormalizedPoint(1f, 1f)
                    else -> NormalizedPoint(0.5f, 0.5f)
                }
                measures += "point" to "${point.x},${point.y}"
                step("focusAt", focusPrediction(capabilities, id), { holder.focusAt(point) })
            }
            FeatureCheck.EXPOSURE_MIN, FeatureCheck.EXPOSURE_ZERO, FeatureCheck.EXPOSURE_MAX -> {
                val range = capabilities.exposureCompensationRange(id)
                val target = when (spec.check) {
                    FeatureCheck.EXPOSURE_MIN -> range?.first
                    FeatureCheck.EXPOSURE_MAX -> range?.last
                    else -> 0
                } ?: 0
                measures += listOf(
                    "range" to range?.let { "${it.first}..${it.last}" },
                    "stepEv" to capabilities.exposureCompensationStep(id),
                    "target" to target,
                )
                step(
                    "setExposureCompensation",
                    exposurePrediction(capabilities, id),
                    { holder.setExposureCompensation(target) },
                ) {
                    val back = readBack(holder.uiState) { it.exposureCompensationIndex == target }
                        .exposureCompensationIndex
                    measures += "indexReadBack" to back
                    if (back != target) checks += "exposure index read back $back, asked $target"
                }
            }
            FeatureCheck.EXPOSURE_RESET -> {
                val hasExposure = capabilities.exposureCompensationRange(id) != null
                step("resetFocusAndExposure", resetPrediction(capabilities, id), { holder.resetFocusAndExposure() }) {
                    val back = readBack(holder.uiState) { it.exposureCompensationIndex == 0 }.exposureCompensationIndex
                    measures += "indexReadBack" to back
                    if (hasExposure && back != 0) checks += "exposure index read back $back after the reset"
                }
            }
        }
    }

    // ---- Frame analysis ----

    private suspend fun analysisCase(case: MatrixCase, spec: AnalysisSpec) {
        log("BEGIN #${case.index} ${case.id}")
        val predicted = analysisPrediction(capabilities, spec)
        val config = CameraConfiguration {
            cameraLens = spec.lens
            frameAnalysis = true
            analysisFormat = spec.format
            analysisResolution = spec.resolution?.let { Resolution(it.width, it.height) }
        }
        withHolder(config) { holder ->
            val opened = open(holder)
            val measures = mutableListOf<Pair<String, Any?>>("readyMs" to opened.readyMs)
            if (!opened.ready) return@withHolder record(case, opened.failure(), predicted, measures)
            val frames = MutableStateFlow(0)
            val last = MutableStateFlow<Triple<MatrixSize, FrameFormat, Int>?>(null)
            val registration = holder.frames.register(FrameSpec(acceptedFormats = setOf(spec.format))) { frame ->
                frames.update { it + 1 }
                last.value = Triple(MatrixSize(frame.width, frame.height), frame.format, frame.rotationDegrees)
            }
            try {
                val active = withTimeoutOrNull(timing.ready) { registration.active.first { it } }
                registration.rejection?.let {
                    return@withHolder record(
                        case,
                        Outcome.Refused("register", it),
                        predicted,
                        measures,
                    )
                }
                val before = frames.value
                delay(timing.analysisWindow)
                val count = frames.value - before
                val frame = last.value
                measures += listOf(
                    "framesInWindow" to count,
                    "fps" to count / timing.analysisWindow.inWholeMilliseconds.toDouble() * 1000,
                    "frameSize" to frame?.first?.toString(),
                    "frameFormat" to frame?.second,
                    "rotationDegrees" to frame?.third,
                )
                val checks = mutableListOf<String>()
                if (active == null) checks += "the registration never became active"
                if (count <= 0) checks += "no frames in ${timing.analysisWindow}"
                if (frame != null && frame.second != spec.format) {
                    checks += "frames arrive as ${frame.second}, asked ${spec.format}"
                }
                record(case, Outcome.Completed, predicted, measures, checks)
            } finally {
                registration.close()
            }
        }
    }

    // ---- Plugins ----

    private suspend fun pluginCase(case: MatrixCase, spec: PluginSpec) {
        log("BEGIN #${case.index} ${case.id}")
        when (spec.plugin) {
            PluginKind.ANALYZER -> {
                val plugin = AnalyzerPlugin()
                watchPlugin(
                    case,
                    plugin,
                    plugin.results,
                    plugin.status,
                    timing.analysisWindow,
                    sceneDependent = false,
                ) { frame ->
                    listOf(
                        "firstSize" to "${frame.width}x${frame.height}",
                        "firstBytes" to frame.bytes.size,
                        "firstMagic" to sniffImageFormat(frame.bytes),
                    )
                }
            }
            PluginKind.OCR -> {
                val plugin = OcrPlugin()
                watchPlugin(case, plugin, plugin.results, plugin.status, timing.pluginWindow, sceneDependent = true) {
                    listOf("firstText" to it.text.take(80), "firstBlocks" to it.blocks.size)
                }
            }
            PluginKind.QR -> {
                val plugin = QRScannerPlugin()
                watchPlugin(case, plugin, plugin.results, plugin.status, timing.pluginWindow, sceneDependent = true) {
                    listOf("firstValue" to it.rawValue?.take(80), "firstFormat" to it.format.name)
                }
            }
            PluginKind.MEDIA_SAVER_PHOTO, PluginKind.MEDIA_SAVER_VIDEO -> mediaSaverCase(case, spec.plugin)
        }
    }

    private suspend fun <R> watchPlugin(
        case: MatrixCase,
        plugin: CameraKPlugin,
        results: Flow<R>,
        status: StateFlow<PluginStatus>,
        window: Duration,
        sceneDependent: Boolean,
        describeFirst: (R) -> List<Pair<String, Any?>>,
    ) = withHolder(CameraConfiguration { cameraLens = CameraLens.BACK }, listOf(plugin)) { holder ->
        val opened = open(holder)
        val measures = mutableListOf<Pair<String, Any?>>(
            "readyMs" to opened.readyMs,
            "sceneDependent" to sceneDependent,
        )
        if (!opened.ready) return@withHolder record(case, opened.failure(), Prediction.NONE, measures)
        val count = MutableStateFlow(0)
        val first = MutableStateFlow<R?>(null)
        val statuses = mutableListOf<String>()
        coroutineScope {
            val collecting = launch {
                results.collect { result ->
                    count.update { it + 1 }
                    if (first.value == null) first.value = result
                }
            }
            val watching = launch { status.collect { statuses += it.toString() } }
            delay(window)
            collecting.cancel()
            watching.cancel()
        }
        measures += listOf(
            "results" to count.value,
            "statuses" to statuses.distinct(),
            "finalStatus" to status.value.toString(),
        )
        first.value?.let { measures += describeFirst(it) }
        val checks = mutableListOf<String>()
        (status.value as? PluginStatus.Failed)?.let { checks += "plugin Failed: ${describe(it.error)}" }
        if (!sceneDependent) {
            if (count.value == 0) checks += "no results in $window"
            val magic = measures.firstOrNull { it.first == "firstMagic" }?.second
            if (count.value > 0 && magic != ImageFormat.JPEG) checks += "the first frame is not a JPEG"
        }
        record(case, Outcome.Completed, Prediction.NONE, measures, checks)
    }

    private suspend fun mediaSaverCase(case: MatrixCase, kind: PluginKind) {
        val saver = platform.newMediaSaver()
            ?: return record(case, Outcome.Threw("this platform has no media saver"), Prediction.NONE)
        withHolder(CameraConfiguration { cameraLens = CameraLens.BACK }, listOf(saver)) { holder ->
            val opened = open(holder)
            val measures = mutableListOf<Pair<String, Any?>>(
                "readyMs" to opened.readyMs,
                "galleryPermission" to saver.galleryPermission,
            )
            if (!opened.ready) return@withHolder record(case, opened.failure(), Prediction.NONE, measures)
            if (kind == PluginKind.MEDIA_SAVER_PHOTO) {
                val image = when (val captured = withTimeoutOrNull(timing.capture) { holder.capture() }) {
                    null -> return@withHolder record(case, Outcome.TimedOut("capture"), Prediction.NONE, measures)
                    is CameraKResult.Failure -> return@withHolder record(
                        case,
                        Outcome.Refused("capture", captured.error),
                        Prediction.NONE,
                        measures,
                    )
                    is CameraKResult.Success -> captured.value
                }
                val saved = command { saver.save(image) }
                files.delete(image.path)
                when (saved) {
                    null -> record(case, Outcome.TimedOut("save"), Prediction.NONE, measures)
                    is CameraKResult.Failure -> record(
                        case,
                        Outcome.Refused("save", saved.error),
                        Prediction.NONE,
                        measures,
                    )
                    is CameraKResult.Success -> {
                        measures += listOf(
                            "displayName" to saved.value.displayName,
                            "asset" to saved.value.asset.displayName,
                        )
                        record(case, Outcome.Completed, Prediction.NONE, measures)
                    }
                }
            } else {
                val video = when (
                    val recorded = record(
                        holder,
                        VideoConfiguration { quality = VideoQuality.HD },
                        measures,
                    )
                ) {
                    is CameraKResult.Failure -> return@withHolder record(
                        case,
                        Outcome.Refused("recording", recorded.error),
                        Prediction.NONE,
                        measures,
                    )
                    null -> return@withHolder record(case, Outcome.TimedOut("recording"), Prediction.NONE, measures)
                    is CameraKResult.Success -> recorded.value
                }
                val saved = command { saver.save(video) }
                files.delete(video.path)
                when (saved) {
                    null -> record(case, Outcome.TimedOut("save"), Prediction.NONE, measures)
                    is CameraKResult.Failure -> record(
                        case,
                        Outcome.Refused("save", saved.error),
                        Prediction.NONE,
                        measures,
                    )
                    is CameraKResult.Success -> {
                        measures += listOf(
                            "displayName" to saved.value.displayName,
                            "asset" to saved.value.asset.displayName,
                        )
                        record(case, Outcome.Completed, Prediction.NONE, measures)
                    }
                }
            }
        }
    }

    // A plain two-second recording, for the saver.
    private suspend fun record(
        holder: CameraKStateHolder,
        config: VideoConfiguration,
        measures: MutableList<Pair<String, Any?>>,
    ): CameraKResult<RecordedVideo>? {
        when (val started = command { holder.startRecording(config) }) {
            null -> return null
            is CameraKResult.Failure -> return started
            is CameraKResult.Success -> Unit
        }
        delay(2.seconds)
        return command { holder.stopRecording() }.also { stopped ->
            (stopped as? CameraKResult.Success)?.value?.let { measures += "videoBytes" to files.size(it.path) }
        }
    }

    // ---- Video ----

    private suspend fun videoGroup(group: List<MatrixCase>) {
        val first = group.first().spec as VideoSpec
        log("BEGIN #${group.first().index} ${group.first().id} (one holder for ${group.size} cases)")
        withHolder(
            CameraConfiguration {
                cameraLens = first.lens
                aspectRatio = first.aspectRatio
            },
        ) { holder ->
            val opened = open(holder)
            if (!opened.ready) {
                group.forEach {
                    record(
                        it,
                        opened.failure(),
                        videoPrediction(capabilities, it.spec as VideoSpec),
                        listOf("readyMs" to opened.readyMs),
                    )
                }
                return@withHolder
            }
            for (case in group) videoCase(holder, case, case.spec as VideoSpec, opened.readyMs)
        }
    }

    private suspend fun videoCase(holder: CameraKStateHolder, case: MatrixCase, spec: VideoSpec, readyMs: Long) {
        log("BEGIN #${case.index} ${case.id}")
        val predicted = videoPrediction(capabilities, spec)
        val measures = mutableListOf<Pair<String, Any?>>("readyMs" to readyMs)
        val checks = mutableListOf<String>()
        val wall = clock.markNow()
        try {
            val config = VideoConfiguration {
                quality = spec.quality
                enableAudio = spec.audio
            }
            when (val started = command { holder.startRecording(config) }) {
                null -> return record(case, Outcome.TimedOut("startRecording"), predicted, measures)
                is CameraKResult.Failure -> return record(
                    case,
                    Outcome.Refused("startRecording", started.error),
                    predicted,
                    measures,
                )
                is CameraKResult.Success -> measures += "startMs" to wall.elapsedNow().inWholeMilliseconds
            }
            delay(timing.recordSegment)
            var active = timing.recordSegment * 2
            when (val paused = command { holder.pauseRecording() }) {
                null -> checks += "pause timed out"
                is CameraKResult.Failure -> {
                    val error = paused.error
                    val foreseen = error is CameraKError.Unsupported &&
                        error.feature == CameraFeature.PAUSE_RESUME &&
                        !capabilities.supportsPauseResume
                    measures += "pause" to describe(error) + if (foreseen) ", predicted" else ""
                    if (!foreseen) checks += "pause: ${describe(error)}"
                    active += timing.pause
                    delay(timing.pause)
                }
                is CameraKResult.Success -> {
                    measures += "pause" to "Success"
                    delay(timing.pause)
                    val resumed = command { holder.resumeRecording() }
                    measures += "resume" to resumed.describeResult()
                    if (resumed !is CameraKResult.Success) checks += "resume: ${measures.last().second}"
                }
            }
            delay(timing.recordSegment)
            measures += "expectedActiveSeconds" to active.inWholeMilliseconds / 1000.0
            val stopping = clock.markNow()
            val stopped = command { holder.stopRecording() }
            measures += listOf(
                "stopMs" to stopping.elapsedNow().inWholeMilliseconds,
                "wallMs" to wall.elapsedNow().inWholeMilliseconds,
            )
            when (stopped) {
                null -> record(case, Outcome.TimedOut("stopRecording"), predicted, measures, checks)
                is CameraKResult.Failure -> record(
                    case,
                    Outcome.Refused("stopRecording", stopped.error),
                    predicted,
                    measures,
                    checks,
                )
                is CameraKResult.Success -> {
                    checks += inspectVideo(stopped.value, spec.audio, measures)
                    record(case, Outcome.Completed, predicted, measures, checks)
                }
            }
        } finally {
            withContext(NonCancellable) { endRecording(holder) }
        }
    }

    // A recording a failed case left live is stopped, and its file deleted, before the next case.
    private suspend fun endRecording(holder: CameraKStateHolder) {
        val state = holder.recordingState.value
        if (state is RecordingState.Recording || state is RecordingState.Paused || state is RecordingState.Starting) {
            log("WARN a recording was still live ($state); stopping it")
            (command { holder.stopRecording() } as? CameraKResult.Success)?.value?.let { files.delete(it.path) }
        }
    }

    private fun inspectVideo(video: RecordedVideo, audio: Boolean?, measures: MeasureList): List<String> {
        val bytes = files.size(video.path)
        val head = if (bytes != null) files.readRange(video.path, 0, 64) else ByteArray(0)
        val brand = videoBrand(head)
        val seconds = bytes?.let { size ->
            mp4DurationSeconds(size) { offset, length ->
                files.readRange(
                    video.path,
                    offset,
                    length,
                )
            }
        }
        files.delete(video.path)
        measures += listOf(
            "fileBytes" to bytes,
            "brand" to brand,
            "containerSeconds" to seconds,
            "reportedSeconds" to video.duration.inWholeMilliseconds / 1000.0,
            "width" to video.width,
            "height" to video.height,
            "bitrateBps" to video.bitrateBps,
            "hasAudio" to video.hasAudio,
        )
        val checks = mutableListOf<String>()
        when (bytes) {
            null -> checks += "no file at ${video.path}"
            0L -> checks += "the file is empty"
        }
        if (bytes != null && bytes > 0) {
            if (brand == null) checks += "no ftyp box: not an MP4 or QuickTime file"
            if (seconds == null || seconds <= 0.0) checks += "no movie duration in the mvhd box"
        }
        if (audio != null && video.hasAudio != audio) checks += "hasAudio ${video.hasAudio}, asked $audio"
        return checks
    }

    // ---- Lifecycle ----

    private suspend fun lifecycleCase(case: MatrixCase, spec: LifecycleSpec) {
        log("BEGIN #${case.index} ${case.id}")
        when (spec.kind) {
            LifecycleKind.START_STOP_20 -> startStopCycles(case)
            LifecycleKind.CLOSED_COMMANDS -> closedCommands(case)
            LifecycleKind.SWITCH_WHILE_RECORDING -> switchWhileRecording(case)
        }
    }

    private suspend fun startStopCycles(case: MatrixCase) = withHolder(backCamera) { holder ->
        val readyTimes = mutableListOf<Long>()
        val checks = mutableListOf<String>()
        repeat(CYCLES) { cycle ->
            val opened = open(holder)
            val reached = opened.state ?: "no Ready in ${timing.ready}"
            if (opened.ready) readyTimes += opened.readyMs else checks += "cycle ${cycle + 1}: $reached"
            holder.stop()
            val idle = withTimeoutOrNull(timing.ready) { holder.cameraState.first { it is CameraKState.Idle } }
            if (idle == null) checks += "cycle ${cycle + 1}: ${holder.cameraState.value} after stop, not Idle"
        }
        val measures = listOf(
            "cycles" to CYCLES,
            "readyMsMin" to readyTimes.minOrNull(),
            "readyMsMax" to readyTimes.maxOrNull(),
            "readyMsMean" to readyTimes.takeIf { it.isNotEmpty() }?.average(),
        )
        record(case, Outcome.Completed, Prediction.NONE, measures, checks)
    }

    private suspend fun closedCommands(case: MatrixCase) = withHolder(backCamera) { holder ->
        val opened = open(holder)
        if (!opened.ready) {
            return@withHolder record(
                case,
                opened.failure(),
                Prediction.NONE,
                listOf("readyMs" to opened.readyMs),
            )
        }
        val closing = clock.markNow()
        val closed = withTimeoutOrNull(timing.close) { holder.closeAndJoin() } != null
        val measures = mutableListOf<Pair<String, Any?>>(
            "readyMs" to opened.readyMs,
            "closeMs" to closing.elapsedNow().inWholeMilliseconds,
            "stateAfterClose" to holder.cameraState.value.toString(),
        )
        val checks = mutableListOf<String>()
        if (!closed) checks += "closeAndJoin did not return within ${timing.close}"
        if (holder.cameraState.value != CameraKState.Closed) checks += "state ${holder.cameraState.value} after close"
        val commands: List<Pair<String, suspend () -> CameraKResult<*>>> = listOf(
            "capture" to { holder.capture() },
            "setZoom" to { holder.setZoom(1f) },
            "setFlashMode" to { holder.setFlashMode(FlashMode.OFF) },
            "setTorchMode" to { holder.setTorchMode(TorchMode.OFF) },
            "selectCamera" to { holder.selectCamera(CameraLens.FRONT) },
            "selectCameraById" to { holder.selectCamera(capabilities.allLenses.first().id) },
            "setTargetOrientation" to { holder.setTargetOrientation(null) },
            "focusAt" to { holder.focusAt(NormalizedPoint(0.5f, 0.5f)) },
            "setExposureCompensation" to { holder.setExposureCompensation(0) },
            "resetFocusAndExposure" to { holder.resetFocusAndExposure() },
            "prepareRecording" to { holder.prepareRecording() },
            "startRecording" to { holder.startRecording() },
            "pauseRecording" to { holder.pauseRecording() },
            "resumeRecording" to { holder.resumeRecording() },
            "stopRecording" to { holder.stopRecording() },
        )
        for ((name, run) in commands) {
            val result = command { run() }
            val described = when (result) {
                null -> "timeout"
                is CameraKResult.Failure -> describe(result.error)
                is CameraKResult.Success -> "Success"
            }
            measures += name to described
            if (described != "Closed") checks += "$name after close returned $described"
        }
        record(case, Outcome.Completed, Prediction.NONE, measures, checks)
    }

    private suspend fun switchWhileRecording(case: MatrixCase) = withHolder(backCamera) { holder ->
        val opened = open(holder)
        val measures = mutableListOf<Pair<String, Any?>>("readyMs" to opened.readyMs)
        if (!opened.ready) return@withHolder record(case, opened.failure(), Prediction.NONE, measures)
        when (val started = command { holder.startRecording(VideoConfiguration { quality = VideoQuality.HD }) }) {
            null -> return@withHolder record(case, Outcome.TimedOut("startRecording"), Prediction.NONE, measures)
            is CameraKResult.Failure -> return@withHolder record(
                case,
                Outcome.Refused("startRecording", started.error),
                Prediction.NONE,
                measures,
            )
            is CameraKResult.Success -> Unit
        }
        val checks = mutableListOf<String>()
        try {
            delay(1.seconds)
            val lensSwitch = command { holder.selectCamera(CameraLens.FRONT) }
            val lensResult = lensSwitch.describeResult()
            measures += "selectCamera(FRONT)" to lensResult
            if (lensResult != "Busy(LENS)") checks += "a lens switch while recording returned $lensResult"
            val current = holder.uiState.value.cameraDeviceType
            val otherType = capabilities.availableDeviceTypes(CameraLens.BACK)
                .firstOrNull { it != current && it != CameraDeviceType.DEFAULT }
            if (otherType != null) {
                val typeResult = command { holder.selectCamera(CameraLens.BACK, otherType) }.describeResult()
                measures += "selectCamera(BACK, $otherType)" to typeResult
                if (typeResult != "Busy(DEVICE_TYPE)") checks += "a device-type switch while recording: $typeResult"
            }
            measures += listOf(
                "lensAfter" to holder.uiState.value.cameraLens,
                "recordingAfter" to holder.recordingState.value::class.simpleName,
            )
            val lensAfter = holder.uiState.value.cameraLens
            val recordingAfter = holder.recordingState.value
            if (lensAfter != CameraLens.BACK) checks += "the lens changed to $lensAfter"
            if (recordingAfter !is RecordingState.Recording) checks += "the recording is $recordingAfter"
            delay(1.seconds)
            when (val stopped = command { holder.stopRecording() }) {
                null -> record(case, Outcome.TimedOut("stopRecording"), Prediction.NONE, measures, checks)
                is CameraKResult.Failure -> record(
                    case,
                    Outcome.Refused("stopRecording", stopped.error),
                    Prediction.NONE,
                    measures,
                    checks,
                )
                is CameraKResult.Success -> {
                    checks += inspectVideo(stopped.value, audio = null, measures)
                    record(case, Outcome.Completed, Prediction.NONE, measures, checks)
                }
            }
        } finally {
            withContext(NonCancellable) { endRecording(holder) }
        }
    }

    // ---- Plumbing ----

    private class Opened(val state: CameraKState?, val readyMs: Long) {
        val ready: Boolean get() = state == CameraKState.Ready

        fun failure(): Outcome = when (state) {
            null -> Outcome.TimedOut("start")
            is CameraKState.Error -> Outcome.Refused("start", state.error)
            else -> Outcome.Threw("the camera was $state before Ready")
        }
    }

    private suspend fun open(holder: CameraKStateHolder): Opened {
        val starting = clock.markNow()
        holder.start()
        val state = withTimeoutOrNull(timing.ready) {
            holder.cameraState.first {
                it == CameraKState.Ready || it is CameraKState.Error || it == CameraKState.Closed
            }
        }
        return Opened(state, starting.elapsedNow().inWholeMilliseconds)
    }

    private suspend fun <T> withHolder(
        config: CameraConfiguration,
        plugins: List<CameraKPlugin> = emptyList(),
        block: suspend (CameraKStateHolder) -> T,
    ): T {
        val holder = platform.newHolder(config, plugins)
        _holder.value = holder
        try {
            return block(holder)
        } finally {
            _holder.value = null
            withContext(NonCancellable) {
                if (withTimeoutOrNull(timing.close) { holder.closeAndJoin() } == null) {
                    log("WARN a holder did not close within ${timing.close}")
                }
            }
        }
    }

    private suspend fun <T> command(block: suspend () -> CameraKResult<T>): CameraKResult<T>? =
        withTimeoutOrNull(timing.command) { block() }

    private suspend fun <T> readBack(flow: StateFlow<T>, wanted: (T) -> Boolean): T =
        withTimeoutOrNull(timing.readBack) { flow.first(wanted) } ?: flow.value

    private fun CameraKResult<*>?.describeResult(): String = when (this) {
        null -> "timeout"
        is CameraKResult.Failure -> describe(error)
        is CameraKResult.Success -> "Success"
    }

    private fun cameraMeasures(ui: CameraUIState, lens: CameraLens, type: CameraDeviceType): List<Pair<String, Any?>> {
        val fellBack = type != CameraDeviceType.DEFAULT && type !in capabilities.availableDeviceTypes(lens)
        return listOf(
            "cameraId" to ui.cameraId,
            "lensReadBack" to ui.cameraLens,
            "deviceTypeReadBack" to ui.cameraDeviceType,
            "deviceTypeUnavailable" to fellBack,
        )
    }

    // The lens must be the one asked for; the device type too, when the lens has it.
    private fun cameraChecks(ui: CameraUIState, lens: CameraLens, type: CameraDeviceType): List<String> = buildList {
        if (ui.cameraLens != lens) add("lens read back ${ui.cameraLens}, asked $lens")
        val offered = type != CameraDeviceType.DEFAULT && type in capabilities.availableDeviceTypes(lens)
        if (offered && ui.cameraDeviceType != type) {
            add("device type read back ${ui.cameraDeviceType}, asked $type")
        }
    }

    private fun record(
        case: MatrixCase,
        outcome: Outcome,
        predicted: Prediction,
        measures: List<Pair<String, Any?>> = emptyList(),
        failedChecks: List<String> = emptyList(),
    ) {
        val judgement = judge(outcome, predicted, failedChecks)
        val record = MatrixRecord(
            case.index,
            case.id,
            case.spec.section.key,
            case.spec.params,
            judgement.verdict,
            judgement.reason,
            measures.toList(),
        )
        writer.append(record)
        recorded += case.index
        counts[judgement.verdict] = (counts[judgement.verdict] ?: 0) + 1
        val bySection = sectionCounts.getOrPut(case.spec.section) { mutableMapOf() }
        bySection[judgement.verdict] = (bySection[judgement.verdict] ?: 0) + 1
        val brief = measures.filter { it.first.endsWith("Ms") || it.first == "fps" }
            .joinToString(" ") { "${it.first}=${it.second}" }
        val verdict = judgement.verdict.label
        val reason = judgement.reason.orEmpty()
        log("[${recorded.size}/$total] #${case.index} $verdict ${case.id} $reason $brief".trimEnd())
        _progress.value = MatrixProgress(recorded.size, total, case.id, counts.toMap())
    }

    private fun log(line: String) = platform.log(line)

    private fun describeCapabilities(c: CameraCapabilities): List<Pair<String, Any?>> = listOf(
        "lenses" to c.allLenses.map {
            listOf(
                "id" to it.id,
                "lens" to it.lens,
                "deviceType" to it.deviceType,
                "minZoom" to it.minZoom,
                "maxZoom" to it.maxZoom,
                "hasFlash" to it.hasFlash,
                "isLogical" to it.isLogical,
                "isExternal" to it.isExternal,
                "flashModes" to c.supportedFlashModes(it.id).map { m -> m.name }.sorted(),
                "torchModes" to c.supportedTorchModes(it.id).map { m -> m.name }.sorted(),
                "focusPoint" to c.supportsFocusPoint(it.id),
                "exposureRange" to c.exposureCompensationRange(it.id)?.let { r -> "${r.first}..${r.last}" },
                "exposureStepEv" to c.exposureCompensationStep(it.id),
            )
        },
        "availableDeviceTypes" to CameraLens.entries.map { lens ->
            lens.name to c.availableDeviceTypes(lens).map { it.name }.sorted()
        },
        "imageFormats" to c.supportedImageFormats.map { it.name }.sorted(),
        "aspectRatios" to c.supportedAspectRatios.map { it.name }.sorted(),
        "videoQualities" to c.supportedVideoQualities.map { it.name }.sorted(),
        "analysisFormats" to c.supportedAnalysisFormats.map { it.name }.sorted(),
        "supportsSquareCapture" to c.supportsSquareCapture,
        "supportsPauseResume" to c.supportsPauseResume,
        "supportsVideoBitrate" to c.supportsVideoBitrate,
    )

    private companion object {
        const val CYCLES = 20

        // Enough for the EXIF block, which can reach 64 KiB, and the HEIF meta box ahead of the frame headers.
        const val HEAD_BYTES = 512 * 1024
    }
}

private typealias MeasureList = MutableList<Pair<String, Any?>>

private val backCamera = CameraConfiguration { cameraLens = CameraLens.BACK }

private fun MatrixSize.sameAs(other: MatrixSize): Boolean =
    this == other || (width == other.height && height == other.width)

// The ratio round-trips through a Float and, on Android, through the camera's own zoom steps.
private fun near(actual: Float, wanted: Float): Boolean = abs(actual - wanted) <= 0.01f * max(1f, abs(wanted))
