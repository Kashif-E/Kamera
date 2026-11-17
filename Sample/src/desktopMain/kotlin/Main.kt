import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kashif.cameraK.utils.CameraKLogger
import org.company.app.App
import org.company.app.devicematrix.DeviceMatrixApp
import org.company.app.devicematrix.matrixArgsFromLaunchArguments
import org.company.app.smoke.SmokeApp
import org.company.app.smoke.smokeArgs
import kotlin.system.exitProcess

// Launched with -cameraKSmoke, the app takes one photo and exits with the verdict; with -cameraKMatrix,
// it runs the device matrix instead of the Sample.
fun main(args: Array<String>) {
    val arguments = args.toList()
    val smoke = smokeArgs(arguments)
    val matrix = matrixArgsFromLaunchArguments(arguments)
    if (smoke != null || "-cameraKMatrixLog" in arguments) CameraKLogger.enabled = true
    var exitCode = 0
    application(exitProcessOnExit = smoke == null) {
        System.setProperty("compose.interop.blending", "true")
        System.setProperty("compose.swing.render.on.layer", "true")

        Window(
            title = if (matrix != null) "CameraK device matrix" else "CameraK",
            // Portrait for the Sample, as on a phone; the matrix keeps its wide window.
            state = if (matrix != null) {
                rememberWindowState(width = 1440.dp, height = 1024.dp)
            } else {
                rememberWindowState(width = 460.dp, height = 780.dp)
            },
            onCloseRequest = ::exitApplication,
        ) {
            when {
                smoke != null -> SmokeApp(smoke) { code ->
                    exitCode = code
                    exitApplication()
                }
                matrix != null -> DeviceMatrixApp(matrix)
                else -> App()
            }
        }
    }
    if (smoke != null) exitProcess(exitCode)
}
