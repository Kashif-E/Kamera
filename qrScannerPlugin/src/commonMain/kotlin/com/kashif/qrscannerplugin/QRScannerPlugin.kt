package com.kashif.qrscannerplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.copyLuma
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.plugin.launchPerSession
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Reads QR codes and barcodes from the camera's analysis frames.
 *
 * While the camera is Ready and the scanner is not paused, it holds one registration on the
 * holder's frame source, at most [QRScannerConfig.maxFps] frames a second. It reads each frame's
 * luma, decodes it (ZXing on Android and desktop, Vision on iOS), and emits each new code on
 * [results]. It works in either analysis format, YUV_420 or RGBA_8888.
 *
 * A decoder failure other than "no code here" fails the plugin: [status] becomes `Failed`, the
 * failure is logged with its throwable and reported once as a `PluginFailed` event, and decoding
 * stops until the plugin is attached again.
 *
 * @sample com.kashif.qrscannerplugin.ScanQrCodesSample
 *
 * @property config What and how this scanner decodes.
 */
public class QRScannerPlugin internal constructor(
    public val config: QRScannerConfig,
    private val decoderFactory: BarcodeDecoderFactory,
) : CameraKPlugin {
    /** A scanner for [config]. */
    public constructor(config: QRScannerConfig = QRScannerConfig()) : this(config, ::createBarcodeDecoder)

    private val paused = MutableStateFlow(false)

    // Idle, Running or Paused: what the plugin itself is doing. status adds the context's failure.
    private val activity = MutableStateFlow<PluginStatus>(PluginStatus.Idle)
    private val _status = MutableStateFlow<PluginStatus>(PluginStatus.Idle)

    /**
     * Running while it holds an active registration; Paused after [pause], or while the registration
     * waits for a frame output; Idle without a Ready session; `Failed` while the current attachment's
     * context has a failure.
     */
    public val status: StateFlow<PluginStatus> = _status.asStateFlow()

    private val _results = MutableSharedFlow<Barcode>(
        extraBufferCapacity = RESULTS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Each code found, once per [QRScannerConfig.dedupeWindow]. Decoding never waits for a
     * collector: one that falls behind loses the oldest codes first. The payload is untrusted input
     * (see [Barcode]).
     */
    public val results: Flow<Barcode> = _results.asSharedFlow()

    override fun onAttach(context: CameraKPluginContext) {
        activity.value = if (paused.value) PluginStatus.Paused else PluginStatus.Idle
        context.scope.launch {
            combine(context.failure, activity) { failure, own -> failure?.let(PluginStatus::Failed) ?: own }
                .collect { _status.value = it }
        }
        context.launchPerSession {
            try {
                combine(paused, context.failure) { paused, failure -> if (failure != null) null else paused }
                    .distinctUntilChanged()
                    .collectLatest { paused ->
                        when (paused) {
                            null -> Unit
                            true -> activity.value = PluginStatus.Paused
                            false -> scan(context)
                        }
                    }
            } finally {
                activity.value = if (paused.value) PluginStatus.Paused else PluginStatus.Idle
            }
        }
    }

    override fun onDetach() {
        activity.value = PluginStatus.Idle
        _status.value = PluginStatus.Idle
    }

    /** Stops decoding and closes the frame registration, so a paused scanner holds no frames. It holds across sessions. */
    public fun pause() {
        paused.value = true
    }

    /** Decodes again after [pause]: at once in a Ready session, otherwise from the next one. */
    public fun resume() {
        paused.value = false
    }

    // Registers for frames and decodes until cancelled: by a pause, a failure, the end of the session or a detach.
    private suspend fun scan(context: CameraKPluginContext) {
        val decoding = FrameDecoding(decoderFactory.create(config.formats)) { error ->
            context.reportFailure(CameraKError.Internal(error))
        }
        context.frames.register(frameSpec(), decoding).use { registration ->
            registration.active.collect { active ->
                activity.value = if (active) PluginStatus.Running else PluginStatus.Paused
            }
        }
    }

    private fun frameSpec() = FrameSpec(
        maxFps = config.maxFps,
        maxRetained = 0,
        synchronous = false,
        acceptedFormats = setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888),
    )

    /**
     * One registration's decoding, on the frame source's worker: the luma into a buffer reused while
     * frames keep their size, the decoder over the area, then the dedupe.
     */
    private inner class FrameDecoding(
        private val decoder: BarcodeDecoder,
        private val onFailure: (Exception) -> Unit,
    ) : FrameAnalyzer {
        private val dedupe = BarcodeDedupe(config.dedupeWindow)
        private var luma = ByteArray(0)
        private var scratch = ByteArray(0)
        private var failed = false
        private var warnedOutsideRegion = false

        override fun analyze(frame: CameraFrame) {
            if (failed) return
            val area = decodeArea(frame.cropRect, config.region, frame.width, frame.height)
            if (area == null) {
                if (!warnedOutsideRegion) {
                    warnedOutsideRegion = true
                    CameraKLogger.w(
                        TAG,
                        "The configured region misses the frame's crop rect; such frames are not decoded",
                    )
                }
                return
            }
            val pixels = frame.width * frame.height
            if (luma.size != pixels) luma = ByteArray(pixels)
            if (frame.format == FrameFormat.RGBA_8888 && scratch.size < frame.width * RGBA_BYTES_PER_PIXEL) {
                scratch = ByteArray(frame.width * RGBA_BYTES_PER_PIXEL)
            }
            frame.copyLuma(luma, 0, scratch)
            val found = try {
                decoder.decode(LumaImage(luma, frame.width, frame.height, area, frame.rotationDegrees))
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Decoding a frame for barcodes failed; the scanner stops", e)
                failed = true
                onFailure(e)
                return
            }
            for (barcode in found) {
                if (dedupe.admit(barcode, frame.timestampNanos)) _results.tryEmit(barcode)
            }
        }
    }

    private companion object {
        const val TAG = "QRScanner"
        const val RESULTS_BUFFER = 16
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}

/**
 * The rectangle to decode: the frame's crop rect, clamped to the frame, intersected with [region]
 * when there is one. Null when that leaves nothing.
 */
internal fun decodeArea(cropRect: FrameRect, region: FrameRect?, width: Int, height: Int): FrameRect? {
    var left = maxOf(cropRect.left, 0)
    var top = maxOf(cropRect.top, 0)
    var right = minOf(cropRect.right, width)
    var bottom = minOf(cropRect.bottom, height)
    if (region != null) {
        left = maxOf(left, region.left)
        top = maxOf(top, region.top)
        right = minOf(right, region.right)
        bottom = minOf(bottom, region.bottom)
    }
    return if (right > left && bottom > top) FrameRect(left, top, right, bottom) else null
}

/** A [QRScannerPlugin] for [config], kept across recompositions and made again when [config] changes. */
@Composable
public fun rememberQRScannerPlugin(config: QRScannerConfig = QRScannerConfig()): QRScannerPlugin =
    remember(config) { QRScannerPlugin(config) }
