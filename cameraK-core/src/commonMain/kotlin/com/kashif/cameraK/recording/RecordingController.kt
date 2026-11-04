package com.kashif.cameraK.recording

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a recording start waits for the platform to confirm it before it gives up with
 * `Timeout(RECORD_START)` and stops whatever the engine began.
 */
internal val RECORDING_START_TIMEOUT: Duration = 5.seconds

/**
 * How long `close()` waits for the recording to finalize before it goes on to close the camera, so
 * a platform that never finalizes cannot keep the holder open.
 */
internal val SHUTDOWN_STOP_TIMEOUT: Duration = 5.seconds
