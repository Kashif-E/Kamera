package dev.kashifmehmood.camerak.engine

import dev.drewhamilton.poko.Poko
import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.DeviceOrientation
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/**
 * The low-rate hardware values the platform reports, read back from its observers and never from
 * the requested value. Compares by value.
 *
 * @property camera The camera the platform has bound; null before the first one.
 * @property targetOrientation The orientation override the platform applies to outputs; null follows the device.
 * @property exposureCompensationIndex The exposure compensation index the platform applies; 0 on a camera
 *   without compensation.
 */
@CameraKEngineApi
@Poko
public class HardwareState(
    public val flashMode: FlashMode,
    public val torchMode: TorchMode,
    public val camera: LensInfo?,
    public val targetOrientation: DeviceOrientation?,
    public val exposureCompensationIndex: Int = 0,
)

/**
 * The hardware the holder wants: seeded from the configuration, changed only by successful
 * commands, and re-applied by the engine after every bind, camera switch and restart. Compares by
 * value.
 *
 * @property exposureCompensationIndex Clamped by the engine into the bound camera's range when it
 *   applies it; a camera without compensation ignores it.
 */
@CameraKEngineApi
@Poko
public class DesiredHardware(
    public val selection: CameraSelection,
    public val flashMode: FlashMode,
    public val torchMode: TorchMode,
    public val zoomRatio: Float,
    public val targetOrientation: DeviceOrientation?,
    public val exposureCompensationIndex: Int = 0,
)

/** Closed hierarchy: which camera to open. Subtypes compare by value. */
@CameraKEngineApi
public sealed interface CameraSelection {
    @Poko
    public class ByLens(public val lens: CameraLens, public val deviceType: CameraDeviceType) : CameraSelection

    /** A `LensInfo.id` from the engine's capabilities (desktop device ids, Android camera ids, iOS uniqueIDs). */
    @Poko
    public class ById(public val id: String) : CameraSelection

    /**
     * The platform's default camera, which a configuration with no lens asks for: the BACK camera of
     * [deviceType] on Android and iOS, the system default device on desktop.
     */
    @Poko
    public class Default(public val deviceType: CameraDeviceType) : CameraSelection
}
