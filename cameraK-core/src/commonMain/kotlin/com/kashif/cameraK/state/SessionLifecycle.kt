package com.kashif.cameraK.state

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a start may wait for the platform to open, hold or fail the camera before the holder
 * gives up with `Timeout(START)`: long enough for a cold CameraX bind or the macOS permission
 * prompt's first frame, short enough that a camera that never answers does not leave a spinner.
 */
internal val START_TIMEOUT: Duration = 10.seconds

/**
 * How long `close()` waits for the engine to release the camera. A platform that never returns
 * cannot keep the holder, or the next holder waiting on the same device, open beyond it.
 */
internal val ENGINE_CLOSE_TIMEOUT: Duration = 5.seconds
