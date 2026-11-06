package com.kashif.cameraK.plugin

import com.kashif.cameraK.error.CameraKError

/**
 * Closed: a plugin's public status. A new status comes only in a major release.
 *
 * A plugin owns [Idle], [Running] and [Paused], and publishes [Failed] with its context's
 * `failure` whenever that is non-null.
 */
public sealed interface PluginStatus {
    /** Attached, but not working: no Ready session, or nothing to do yet. */
    public data object Idle : PluginStatus

    /** Working on the current session. */
    public data object Running : PluginStatus

    /** Paused by the app, or waiting for a frame output (the Android late bind, the iOS topology freeze). */
    public data object Paused : PluginStatus

    /** This attachment failed with [error]; it stays failed until the plugin is attached again. */
    public class Failed(public val error: CameraKError) : PluginStatus {
        override fun toString(): String = "Failed(error=${error::class.simpleName})"
    }
}
