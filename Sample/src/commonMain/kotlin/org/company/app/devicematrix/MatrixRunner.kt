package org.company.app.devicematrix

import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.mediasaverplugin.MediaSaverPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the runner needs from the platform it runs on. */
internal interface MatrixPlatform {
    val platformName: String
    val deviceDescription: String

    /** Where report.json and report.md go: `files/camerak-matrix` on Android, `Documents/camerak-matrix` on iOS. */
    val reportDirectory: String
    val files: MatrixFiles

    /** A production holder, as the Sample builds one. */
    fun newHolder(config: CameraConfiguration, plugins: List<CameraKPlugin> = emptyList()): CameraKStateHolder

    /** A media saver that saves only when asked; null where the platform has none. */
    fun newMediaSaver(): MediaSaverPlugin?

    /** The permission's status as the app last read it; null before it has. */
    fun permission(permission: CameraKPermission): PermissionStatus?

    /** One line under the tag CameraKMatrix. */
    fun log(line: String)
}

/** How long each step may take, and how long the runner records or watches. */
internal class MatrixTiming(
    val ready: Duration = 15.seconds,
    val capture: Duration = 20.seconds,
    val command: Duration = 10.seconds,
    val close: Duration = 10.seconds,
    val readBack: Duration = 2.seconds,
    val permissionWait: Duration = 180.seconds,
    val recordSegment: Duration = 1500.milliseconds,
    val pause: Duration = 1.seconds,
    val analysisWindow: Duration = 3.seconds,
    val pluginWindow: Duration = 5.seconds,
)

internal data class MatrixProgress(
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "starting",
    val counts: Map<Verdict, Int> = emptyMap(),
    val finished: Boolean = false,
)

/**
 * Runs the device matrix against real holders from [platform], one case after another, writing each
 * result to the report as it finishes. [holder] is the holder the preview shows.
 */
internal class MatrixRunner(
    private val platform: MatrixPlatform,
    private val args: MatrixArgs,
    private val timing: MatrixTiming = MatrixTiming(),
) {
    private val _holder = MutableStateFlow<CameraKStateHolder?>(null)
    val holder: StateFlow<CameraKStateHolder?> = _holder.asStateFlow()

    private val _progress = MutableStateFlow(MatrixProgress())
    val progress: StateFlow<MatrixProgress> = _progress.asStateFlow()

    suspend fun run(): Unit = TODO()
}
