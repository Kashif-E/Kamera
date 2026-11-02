package com.kashif.cameraK.capture

import com.kashif.cameraK.utils.CameraKEngineApi
import dev.drewhamilton.poko.Poko
import kotlin.time.Duration

/**
 * A finished recording: a real file in app-owned storage. Compares by value.
 *
 * @property width The width actually written, on every platform.
 * @property height The height actually written.
 * @property bitrateBps The actual bitrate, where the platform reports it.
 * @property hasAudio Whether the file has an audio track, from the audio connection that was active.
 */
@Poko
public class RecordedVideo @CameraKEngineApi constructor(
    public val path: String,
    public val duration: Duration,
    public val width: Int,
    public val height: Int,
    public val bitrateBps: Int?,
    public val hasAudio: Boolean,
)
