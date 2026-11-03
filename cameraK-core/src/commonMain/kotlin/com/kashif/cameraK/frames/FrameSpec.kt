package com.kashif.cameraK.frames

/**
 * How one analyzer wants frames delivered. Format and size are not here: they are session
 * properties (`CameraConfiguration.analysisFormat` and `analysisResolution`), fixed at bind.
 *
 * @property maxFps The throttle; it counts attempts and is checked before any copy. Null is unthrottled.
 * @property maxRetained Frames this analyzer may hold through `retain()` before it is skipped.
 * @property synchronous Run on the producer thread with the platform buffer itself (zero copy).
 *   Holding the frame, or being slow, delays every analyzer; only short synchronous decoders set this.
 * @property acceptedFormats The session formats this analyzer can read. Not a request: a session in
 *   another format rejects the registration.
 */
public class FrameSpec(
    public val maxFps: Float? = null,
    public val maxRetained: Int = 1,
    public val synchronous: Boolean = false,
    public val acceptedFormats: Set<FrameFormat> = FrameFormat.entries.toSet(),
)
