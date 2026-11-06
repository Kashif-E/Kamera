package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import dev.drewhamilton.poko.Poko

/**
 * The low-rate hardware values the platform reports, read back from its observers and never from
 * the requested value. Compares by value.
 *
 * @property camera The camera the platform has bound; null before the first one.
 * @property targetOrientation The orientation override the platform applies to outputs; null follows the device.
 */
@CameraKEngineApi
@Poko
public class HardwareState(
    public val flashMode: FlashMode,
    public val torchMode: TorchMode,
    public val camera: LensInfo?,
    public val targetOrientation: DeviceOrientation?,
)

/**
 * The hardware the holder wants: seeded from the configuration, changed only by successful
 * commands, and re-applied by the engine after every bind, camera switch and restart. Compares by
 * value.
 */
@CameraKEngineApi
@Poko
public class DesiredHardware(
    public val selection: CameraSelection,
    public val flashMode: FlashMode,
    public val torchMode: TorchMode,
    public val zoomRatio: Float,
    public val targetOrientation: DeviceOrientation?,
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
