package dev.kashifmehmood.camerak.state

import dev.drewhamilton.poko.Poko
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.DeviceOrientation
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode

/**
 * The camera's low-rate properties as the platform reports them. The holder derives every field;
 * nothing else writes it, so a command shows here only once the platform has applied it. Before the
 * first bind the camera fields are null and DEFAULT; the values are confirmed once `cameraState` is
 * Ready. Zoom is the holder's `zoom` flow, the recording its `recordingState`, and the image format
 * and quality are the configuration's. A plain class that compares by value: it can gain a field in
 * a minor release, and only the holder builds one.
 *
 * camerak-core carries no Compose annotations; camerak's compose-stability.conf marks this class
 * stable for Compose, so only changed properties trigger recomposition.
 *
 * @property flashMode The flash mode for captures.
 * @property torchMode The torch mode.
 * @property cameraLens The bound camera's facing; null before the first bind.
 * @property cameraDeviceType The bound camera's device type; DEFAULT before the first bind.
 * @property isCapturing Whether a capture is in flight: [pendingCaptures] is above zero.
 * @property pendingCaptures How many captures are in flight.
 * @property cameraId The bound camera's `LensInfo.id`; null before the first bind.
 * @property targetOrientation The orientation outputs are fixed to; null follows the device.
 * @property exposureCompensationIndex The exposure compensation index the platform applies, in the
 *   bound camera's `CameraCapabilities.exposureCompensationRange`; 0 on a camera without compensation.
 *
 * @sample dev.kashifmehmood.camerak.samples.cameraUIStateSample
 * @sample dev.kashifmehmood.camerak.samples.cameraUIStateFlashButtonSample
 */
@Poko
public class CameraUIState internal constructor(
    public val flashMode: FlashMode? = null,
    public val torchMode: TorchMode? = null,
    public val cameraLens: CameraLens? = null,
    public val cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    public val isCapturing: Boolean = false,
    public val pendingCaptures: Int = 0,
    public val cameraId: String? = null,
    public val targetOrientation: DeviceOrientation? = null,
    public val exposureCompensationIndex: Int = 0,
)
