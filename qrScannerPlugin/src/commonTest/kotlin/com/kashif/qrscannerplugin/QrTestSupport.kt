@file:OptIn(CameraKEngineApi::class)

package com.kashif.qrscannerplugin

import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertIs

/** What a fake decoder was handed for one frame: the buffer itself, and a copy of the luma in it. */
internal class SeenImage(image: LumaImage) {
    val buffer: ByteArray = image.luma
    val luma: ByteArray = image.luma.copyOf(image.width * image.height)
    val width: Int = image.width
    val height: Int = image.height
    val area: List<Int> = image.area.edges()
    val rotationDegrees: Int = image.rotationDegrees
}

/** A decoder factory whose decoders record each image and answer with [answer]. */
internal class FakeDecoders(var answer: (LumaImage) -> List<Barcode> = { emptyList() }) : BarcodeDecoderFactory {
    val created = mutableListOf<Set<BarcodeFormat>>()
    val images = mutableListOf<SeenImage>()

    override fun create(formats: Set<BarcodeFormat>): BarcodeDecoder {
        created += formats
        return BarcodeDecoder { image ->
            images += SeenImage(image)
            answer(image)
        }
    }
}

/**
 * The holder's frame source as its plugins see it: each registration's spec, and whether it was
 * closed. With [rejectWith] set, a registration is rejected without reaching the fake engine's source;
 * with [inactive] set, it reaches it but reports itself inactive, as one waiting for a frame output.
 */
internal class SpyFrames(private val inner: FrameSource) : FrameSource {
    val registrations = mutableListOf<SpyRegistration>()
    var rejectWith: CameraKError? = null
    var inactive = false

    val open: List<SpyRegistration> get() = registrations.filterNot { it.closed }

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration {
        val rejection = rejectWith
        val registration = when {
            rejection != null -> SpyRegistration(spec, null, MutableStateFlow(false), rejection)
            else -> {
                val real = inner.register(spec, analyzer)
                SpyRegistration(spec, real, if (inactive) MutableStateFlow(false) else real.active, null)
            }
        }
        registrations += registration
        return registration
    }
}

internal class SpyRegistration(
    val spec: FrameSpec,
    private val inner: FrameRegistration?,
    override val active: StateFlow<Boolean>,
    override val rejection: CameraKError?,
) : FrameRegistration {
    var closed = false
        private set

    override fun close() {
        closed = true
        inner?.close()
    }
}

/** The fake engine, with its frame source seen through [spyFrames]. */
internal class SpyEngine(val fake: FakeCameraEngine) : CameraEngine by fake {
    val spyFrames = SpyFrames(fake.frames)
    override val frames: FrameSource get() = spyFrames
}

/** A holder over a fake engine with [QrSession.plugin] attached at creation, and the events it emitted. */
internal class QrSession(
    val plugin: QRScannerPlugin,
    val holder: CameraKStateHolder,
    val fake: FakeCameraEngine,
    val frames: SpyFrames,
    val events: List<CameraKEvent>,
)

internal fun TestScope.qrSession(
    plugin: QRScannerPlugin,
    config: CameraConfiguration = CameraConfiguration(),
): QrSession {
    val fake = FakeCameraEngine().apply { configuration = config }
    val engine = SpyEngine(fake)
    val holder = CameraKStateHolder(config, { _, _ -> engine }, listOf(plugin), cameraKTestEnvironment())
    val events = mutableListOf<CameraKEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
    return QrSession(plugin, holder, fake, engine.spyFrames, events)
}

internal fun TestScope.startReady(session: QrSession) {
    session.holder.start()
    runCurrent()
    assertIs<CameraKState.Ready>(session.holder.cameraState.value, "state is ${session.holder.cameraState.value}")
}

internal fun TestScope.collectResults(plugin: QRScannerPlugin): List<Barcode> {
    val results = mutableListOf<Barcode>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { plugin.results.collect { results += it } }
    return results
}

/** Nanoseconds between test frames: more than 1 / maxFps at the default 5, so a gated source admits each. */
internal const val FRAME_GAP_NANOS = 250_000_000L

/** The [index]th test frame, [FRAME_GAP_NANOS] after the one before. */
internal fun blankFrame(index: Int, width: Int = 8, height: Int = 8, cropRect: FrameRect? = null): TestFrame =
    TestFrame.luma(
        width,
        height,
        ByteArray(width * height),
        timestampNanos = index * FRAME_GAP_NANOS,
        cropRect = cropRect,
    )

internal fun qr(text: String, format: BarcodeFormat = BarcodeFormat.QR_CODE): Barcode =
    Barcode(text, null, format, null)

internal fun FrameRect.edges(): List<Int> = listOf(left, top, right, bottom)

/** Every log line at WARN or above while installed. */
internal class LoggedProblems {
    val entries = mutableListOf<Pair<String, Throwable?>>()

    fun install() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            if (level == CameraKLogger.Level.WARN || level == CameraKLogger.Level.ERROR) entries += message to throwable
        }
    }

    fun restore() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }
}
