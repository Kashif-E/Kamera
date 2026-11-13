import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kashif.cameraK.utils.CameraKLogger
import org.company.app.App
import org.company.app.smoke.SmokeApp
import org.company.app.smoke.smokeArgs
import kotlin.system.exitProcess

// Launched with -cameraKSmoke (CI on Linux and Windows), the app opens the camera, takes one photo and
// exits with the verdict instead of running the Sample.
fun main(args: Array<String>) {
    val smoke = smokeArgs(args.toList())
    // A smoke run's log shows why a camera did not open.
    if (smoke != null) CameraKLogger.enabled = true
    var exitCode = 0
    // Compose exits the process with 0 when the last window closes unless told not to.
    application(exitProcessOnExit = smoke == null) {
        System.setProperty("compose.interop.blending", "true")
        System.setProperty("compose.swing.render.on.layer", "true")

        Window(
            title = "CameraK",
            state = rememberWindowState(width = 1440.dp, height = 1024.dp),
            onCloseRequest = ::exitApplication,
        ) {
            if (smoke != null) {
                SmokeApp(smoke) { code ->
                    exitCode = code
                    exitApplication()
                }
            } else {
                App()
            }
        }
    }
    if (smoke != null) exitProcess(exitCode)
}
