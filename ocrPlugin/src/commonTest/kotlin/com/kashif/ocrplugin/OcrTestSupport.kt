@file:OptIn(CameraKEngineApi::class)

package com.kashif.ocrPlugin

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertIs

/** What a fake live recognizer was handed: a copy, because the plugin reuses its luma buffer. */
internal class SeenImage(val luma: List<Byte>, val width: Int, val height: Int, val rotationDegrees: Int)

/** A still recognizer that records how it was used. */
internal class FakeStill(val level: OcrLevel, private val answer: suspend (String) -> List<FoundBlock>) :
    StillTextRecognizer {
    var closes = 0
    val paths = mutableListOf<String>()

    override suspend fun recognize(path: String): List<FoundBlock> {
        paths += path
        return answer(path)
    }

    override fun close() {
        closes++
    }
}

/** Recognizers that record what they are asked and answer with [liveAnswer] and [stillAnswer]. */
internal class FakeRecognizers : TextRecognizerFactory {
    val seen = mutableListOf<SeenImage>()
    val liveLevels = mutableListOf<OcrLevel>()
    var liveCloses = 0
    var liveAnswer: (LumaImage) -> List<FoundBlock> = { listOf(FoundBlock(TEXT, null, null)) }
    var liveFailure: Throwable? = null
    val stills = mutableListOf<FakeStill>()
    var stillAnswer: suspend (String) -> List<FoundBlock> = { listOf(FoundBlock(STILL_TEXT, null, null)) }

    override fun live(level: OcrLevel): LiveTextRecognizer {
        liveFailure?.let { throw it }
        liveLevels += level
        return object : LiveTextRecognizer {
            override fun recognize(image: LumaImage): List<FoundBlock> {
                seen += SeenImage(
                    image.bytes.copyOf(image.width * image.height).toList(),
                    image.width,
                    image.height,
                    image.rotationDegrees,
                )
                return liveAnswer(image)
            }

            override fun close() {
                liveCloses++
            }
        }
    }

    override fun still(level: OcrLevel): StillTextRecognizer = FakeStill(level, stillAnswer).also { stills += it }

    companion object {
        const val TEXT = "INVOICE 42"
        const val STILL_TEXT = "RECEIPT"
    }
}

/**
 * A frame source over [delegate] that counts the registrations still open, and rejects every
 * registration with [rejection] when it is set.
 */
internal class CountingFrames(private val delegate: FrameSource, private val rejection: CameraKError? = null) :
    FrameSource {
    var open = 0
        private set

    override fun register(spec: FrameSpec, analyzer: FrameAnalyzer): FrameRegistration {
        if (rejection != null) {
            return object : FrameRegistration {
                override val active: StateFlow<Boolean> = MutableStateFlow(false)
                override val rejection: CameraKError = this@CountingFrames.rejection

                override fun close() = Unit
            }
        }
        val registration = delegate.register(spec, analyzer)
        open++
        return object : FrameRegistration by registration {
            private var closed = false

            override fun close() {
                if (!closed) {
                    closed = true
                    open--
                }
                registration.close()
            }
        }
    }
}

/** A holder over a fake engine with an OCR plugin attached at creation, and its events. */
internal class OcrSession(
    val holder: CameraKStateHolder,
    val engine: FakeCameraEngine,
    val frames: CountingFrames,
    val events: List<CameraKEvent>,
)

internal fun TestScope.ocrSession(
    plugin: OcrPlugin,
    format: FrameFormat = FrameFormat.YUV_420,
    rejection: CameraKError? = null,
): OcrSession {
    val engine = FakeCameraEngine()
    val frames = CountingFrames(engine.frames, rejection)
    val wrapped = object : CameraEngine by engine {
        override val frames: FrameSource get() = frames
    }
    val holder = CameraKStateHolder(
        CameraConfiguration { analysisFormat = format },
        { config, _ -> wrapped.also { engine.configuration = config } },
        listOf(plugin),
        cameraKTestEnvironment(),
    )
    val events = mutableListOf<CameraKEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
    return OcrSession(holder, engine, frames, events)
}

internal fun TestScope.startReady(session: OcrSession) {
    session.holder.start()
    runCurrent()
    assertIs<CameraKState.Ready>(session.holder.cameraState.value, "state is ${session.holder.cameraState.value}")
}

/** A luma frame of [width] x [height] whose pixels count up from 0, stamped [timestampNanos]. */
internal fun countingFrame(
    width: Int = 4,
    height: Int = 4,
    timestampNanos: Long = 0,
    rotationDegrees: Int = 0,
): TestFrame = TestFrame.luma(
    width,
    height,
    ByteArray(width * height) { it.toByte() },
    rotationDegrees = rotationDegrees,
    timestampNanos = timestampNanos,
)

/** A capture for [OcrPlugin.recognize]; the fake recognizers never open its file. */
internal fun capture(path: String = "/captures/IMG_1.jpg"): CapturedImage = CapturedImage(path, ImageFormat.JPEG, 4, 4)

internal const val SECOND_NANOS = 1_000_000_000L
