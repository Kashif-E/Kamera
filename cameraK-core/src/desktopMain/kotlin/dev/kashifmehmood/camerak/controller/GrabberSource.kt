package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.platform.DesktopGrabberFactory
import dev.kashifmehmood.camerak.utils.CameraKLogger
import dev.kashifmehmood.camerak.utils.CameraKPlatformApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber

/** Where a grab loop gets its grabber for a device. */
internal interface GrabberSource {
    /** What the per-device lock is keyed on for [device]. */
    fun deviceKey(device: LensInfo): Any

    /** Whether a failed open is retried once without CameraK's size and frame rate (§4.1 row 3). */
    val canRelax: Boolean

    /**
     * Whether an FFmpeg grabber from this source is set to read macOS cameras through AVFoundation,
     * as the 1.x loop set every one. False for the app's factory, whose grabber is started as returned.
     */
    val formatsForMac: Boolean get() = true

    /** A new grabber for [device]; [constrained] asks for CameraK's size and frame rate. */
    fun create(device: LensInfo, constrained: Boolean): FrameGrabber

    /** A new grabber for [device] asking for [size], one of its own modes, at CameraK's frame rate. */
    fun create(device: LensInfo, size: Pair<Int, Int>): FrameGrabber = create(device, constrained = true)

    /** What is known of the mode a constrained open of [device] asks for; null when nothing is. */
    fun knownMode(device: LensInfo): KnownMode? = null
}

/** What the camera's own list of modes says of the mode a constrained open asks for. */
internal sealed interface KnownMode {
    /**
     * The camera has a [size] mode at CameraK's frame rate: the configuration's targetResolution, or the
     * size it has nearest to it.
     */
    data class Listed(val size: Pair<Int, Int>) : KnownMode

    /** The camera has no mode at CameraK's default size and frame rate, asked for when no target is set. */
    data object DefaultMissing : KnownMode
}

/**
 * The size of [sizes] a [target] opens at, as CameraX picks one (closest higher, then lower): [target]
 * when listed, turned to landscape when the camera lists no portrait size; else the smallest size that holds the target once cropped to the target's aspect ratio;
 * else the size whose crop holds the most of it.
 */
internal fun nearestSize(sizes: List<Pair<Int, Int>>, target: Pair<Int, Int>): Pair<Int, Int> {
    require(sizes.isNotEmpty()) { "no size to choose from" }
    if (target in sizes) return target
    // Desktop cameras stream landscape: a portrait target written for the phones is turned.
    if (target.second > target.first && sizes.none { it.second > it.first }) {
        return nearestSize(sizes, target.second to target.first)
    }
    val ratio = target.first.toDouble() / target.second

    // The largest rectangle of the target's aspect ratio inside a size.
    fun crop(size: Pair<Int, Int>) = minOf(size.first.toDouble(), size.second * ratio).let { it to it / ratio }
    val area = compareBy<Pair<Int, Int>> { it.first.toLong() * it.second }
    return sizes.filter { crop(it).let { (w, h) -> w >= target.first && h >= target.second } }.minWithOrNull(area)
        ?: sizes.maxWith(compareBy<Pair<Int, Int>> { crop(it).let { (w, h) -> w * h } }.then(area.reversed()))
}

/** A started grabber, and whether it opened only at the camera's own mode. */
internal class OpenedDevice(val grabber: FrameGrabber, val relaxed: Boolean)

/**
 * Opens [device] at CameraK's size and frame rate and, if that fails and the source allows it,
 * once more at the camera's own mode (§4.1 row 3: CameraGrabber set both on every open, so a camera
 * without that mode never opened). [own] is told which grabber the caller must release if this
 * throws or is cancelled, as `start()` is called on it: an open cancelled before that leaves nothing
 * to release. The first grabber of a retried open is released here. Each `start()` runs
 * interruptibly, so cancelling the open interrupts it. Missing natives are not retried. [os] decides
 * how an FFmpeg grabber reads the camera.
 */
internal suspend fun GrabberSource.open(
    device: LensInfo,
    own: (FrameGrabber?) -> Unit,
    release: (FrameGrabber) -> Unit,
    os: OperatingSystem = currentOperatingSystem(),
): OpenedDevice {
    // A failed avfoundation open followed at once by the retry's open and release crashed macOS's main
    // thread (objc_release on an AVCaptureDeviceInput), so a size the camera is known to lack is not
    // asked: the nearest it has is.
    val known = if (canRelax) knownMode(device) else null
    if (known == KnownMode.DefaultMissing) {
        CameraKLogger.w(TAG, "The camera has no mode at CameraK's size and frame rate; opening it at its own mode")
        return openOwnMode(device, own, release, os, earlier = null)
    }
    val listed = (known as? KnownMode.Listed)?.size
    val constrained = if (listed != null) create(device, listed) else create(device, constrained = true)
    prepare(constrained, os)
    try {
        blockingCall {
            own(constrained)
            constrained.start()
        }
        return OpenedDevice(constrained, relaxed = false)
    } catch (e: Exception) {
        if (e is CancellationException || !canRelax || JavaCvErrorMapper.isNativeLibraryMissing(e)) throw e
        // The camera lists the size: the failure is the camera's.
        if (known is KnownMode.Listed) throw e
        CameraKLogger.w(TAG, "Opening the camera at CameraK's size and frame rate failed; retrying at its own mode", e)
        own(null)
        release(constrained)
        return openOwnMode(device, own, release, os, earlier = e)
    }
}

// The camera at its own mode; [earlier] is the constrained open's failure, kept as suppressed.
private suspend fun GrabberSource.openOwnMode(
    device: LensInfo,
    own: (FrameGrabber?) -> Unit,
    release: (FrameGrabber) -> Unit,
    os: OperatingSystem,
    earlier: Exception?,
): OpenedDevice {
    val relaxed = create(device, constrained = false).also { prepare(it, os) }
    try {
        blockingCall {
            own(relaxed)
            relaxed.start()
        }
        return OpenedDevice(relaxed, relaxed = true)
    } catch (retry: Exception) {
        if (retry !is CancellationException && earlier != null) retry.addSuppressed(earlier)
        // macOS asks for CameraK's rate first (ffmpegGrabber); a camera whose modes top out
        // elsewhere, such as 29.97, opens only at avfoundation's own default rate.
        if (retry is CancellationException || os != OperatingSystem.MAC || relaxed.frameRate <= 0) throw retry
        own(null)
        release(relaxed)
        return openAtDefaultRate(device, own, os, retry)
    }
}

private suspend fun GrabberSource.openAtDefaultRate(
    device: LensInfo,
    own: (FrameGrabber?) -> Unit,
    os: OperatingSystem,
    earlier: Exception,
): OpenedDevice {
    CameraKLogger.w(TAG, "Opening the camera at its own mode and CameraK's frame rate failed; retrying at its rate")
    val grabber = create(device, constrained = false).also {
        prepare(it, os)
        it.frameRate = 0.0
    }
    try {
        blockingCall {
            own(grabber)
            grabber.start()
        }
    } catch (last: Exception) {
        if (last !is CancellationException) last.addSuppressed(earlier)
        throw last
    }
    return OpenedDevice(grabber, relaxed = true)
}

// FFmpeg reads macOS cameras through AVFoundation; the 1.x loop set the format on every FFmpeg grabber.
private fun GrabberSource.prepare(grabber: FrameGrabber, os: OperatingSystem) {
    if (formatsForMac && grabber is FFmpegFrameGrabber && os == OperatingSystem.MAC) {
        grabber.format = "avfoundation"
    }
}

/**
 * Runs a blocking JavaCV [call] interruptibly: cancelling the caller interrupts it. The call's own
 * throwable is rethrown as the same instance; thrown out of runInterruptible it would arrive as
 * kotlinx-coroutines' recovered copy, and the error mapper and `CameraKError.cause` keep the
 * platform's own.
 */
internal suspend fun <T> blockingCall(call: () -> T): T = runInterruptible {
    try {
        Result.success(call())
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}.getOrThrow()

private const val TAG = "CameraK"

/**
 * A grabber the app built itself (the 1.x `customGrabber`): its own device, opened as it is, with
 * no CameraK size or frame rate to drop on a retry.
 */
internal class CustomGrabberSource(private val grabber: FrameGrabber) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = grabber
    override val canRelax: Boolean get() = false

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = grabber
}

/**
 * The enumerated cameras of [devices], each opened by its `LensInfo.id` at [targetResolution] (720p
 * when null), or the size the camera lists nearest to it, and CameraK's frame rate, or at the camera's
 * own mode on the retry. The lock is keyed on the id (§2.4 desktop). Tests that reach a real camera get
 * [defaultCameraOverride] instead.
 */
internal class EnumeratedDeviceSource(
    private val devices: DesktopDevices,
    private val targetResolution: Pair<Int, Int>?,
) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = device.id

    override val canRelax: Boolean get() = true

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = defaultCameraOverride?.invoke()
        ?: devices.open(device.id, if (constrained) targetResolution ?: DEFAULT_CAMERA_RESOLUTION else null)

    override fun create(device: LensInfo, size: Pair<Int, Int>): FrameGrabber =
        defaultCameraOverride?.invoke() ?: devices.open(device.id, size)

    override fun knownMode(device: LensInfo): KnownMode? {
        if (defaultCameraOverride != null) return null
        val sizes = devices.sizes(device.id, DEFAULT_CAMERA_FRAME_RATE) ?: return null
        val target = targetResolution
        return when {
            sizes.isEmpty() -> KnownMode.DefaultMissing
            target != null -> KnownMode.Listed(
                nearestSize(sizes, target).also {
                    if (it != target) {
                        CameraKLogger.w(
                            TAG,
                            "The camera has no mode at the targetResolution; opening it at the nearest size it has",
                        )
                    }
                },
            )
            DEFAULT_CAMERA_RESOLUTION in sizes -> null
            else -> KnownMode.DefaultMissing
        }
    }
}

/**
 * The app's [DesktopGrabberFactory]: every open, watchdog reopens included, asks it for the selected
 * device's id. Its grabber is started as the app built it: no CameraK size or frame rate, so nothing
 * to drop on a retry, and, unlike the 1.x `customGrabber`, no AVFoundation format on macOS, so a
 * stream such as `rtsp://` opens as a stream. The lock is keyed on the id.
 */
@OptIn(CameraKPlatformApi::class)
internal class FactoryGrabberSource(private val factory: DesktopGrabberFactory) : GrabberSource {
    override fun deviceKey(device: LensInfo): Any = device.id

    override val canRelax: Boolean get() = false

    override val formatsForMac: Boolean get() = false

    override fun create(device: LensInfo, constrained: Boolean): FrameGrabber = factory.create(device.id)
}
