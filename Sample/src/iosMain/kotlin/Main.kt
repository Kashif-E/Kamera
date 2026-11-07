import androidx.compose.ui.window.ComposeUIViewController
import org.company.app.App
import org.company.app.devicematrix.DeviceMatrixApp
import org.company.app.devicematrix.matrixArgsFromLaunchArguments
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

// Launched with -cameraKMatrix, the app runs the device matrix instead of the Sample.
@Suppress("ktlint:standard:function-naming")
fun MainViewController(): UIViewController {
    val matrix = matrixArgsFromLaunchArguments(NSProcessInfo.processInfo.arguments.map { it.toString() })
    return ComposeUIViewController { if (matrix != null) DeviceMatrixApp(matrix) else App() }
}
