package org.company.app.devicematrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.kashifmehmood.camerak.mediasaver.MediaSaverConfig
import dev.kashifmehmood.camerak.mediasaver.MediaSaverPlugin
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKPlugin
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

@Composable
internal actual fun rememberMatrixPlatform(permission: (CameraKPermission) -> PermissionStatus?): MatrixPlatform =
    remember { DesktopMatrixPlatform(permission) }

@Composable
internal actual fun KeepScreenOn() = Unit

private class DesktopMatrixPlatform(private val permissions: (CameraKPermission) -> PermissionStatus?) :
    MatrixPlatform {
    private val platformContext = CameraKPlatformContext()
    override val platformName = "desktop"
    override val deviceDescription = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    override val reportDirectory: String = File(System.getProperty("user.home"), "camerak-matrix").path
    override val files: MatrixFiles = platformFiles
    override val promptsOnOpen = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    override fun newHolder(config: CameraConfiguration, plugins: List<CameraKPlugin>): CameraKStateHolder =
        CameraKStateHolder(config, platformContext, plugins)

    override fun newMediaSaver(): MediaSaverPlugin = MediaSaverPlugin(
        MediaSaverConfig {
            autoSave = false
        },
        platformContext,
    )

    override fun permission(permission: CameraKPermission): PermissionStatus? = permissions(permission)

    override fun audioPeakDbfs(path: String): Double? = try {
        FFmpegFrameGrabber(path).use { grabber ->
            grabber.start()
            var peak = 0.0
            while (true) {
                val frame = grabber.grabSamples() ?: break
                for (buffer in frame.samples.orEmpty()) {
                    when (buffer) {
                        is FloatBuffer -> for (i in buffer.position() until buffer.limit()) {
                            peak = max(peak, abs(buffer.get(i).toDouble()))
                        }
                        is ShortBuffer -> for (i in buffer.position() until buffer.limit()) {
                            peak = max(peak, abs(buffer.get(i).toDouble()) / 32768.0)
                        }
                    }
                }
            }
            grabber.stop()
            if (peak == 0.0) Double.NEGATIVE_INFINITY else 20 * log10(peak)
        }
    } catch (e: Exception) {
        log("WARN the recording's audio could not be decoded: $e")
        null
    }

    override fun log(line: String) {
        println("CameraKMatrix: $line")
    }
}
