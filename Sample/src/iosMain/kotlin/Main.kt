import androidx.compose.ui.window.ComposeUIViewController
import dev.kashifmehmood.camerak.utils.CameraKLogger
import org.company.app.App
import org.company.app.devicematrix.DeviceMatrixApp
import org.company.app.devicematrix.matrixArgsFromLaunchArguments
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

// Launched with -cameraKMatrix, the app runs the device matrix instead of the Sample.
@Suppress("ktlint:standard:function-naming")
fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val matrix = matrixArgsFromLaunchArguments(arguments)
    // -cameraKMatrixLog: the library's own log lines go to the console, with each failure's cause.
    if ("-cameraKMatrixLog" in arguments) CameraKLogger.enabled = true
    return ComposeUIViewController { if (matrix != null) DeviceMatrixApp(matrix) else App() }
}
