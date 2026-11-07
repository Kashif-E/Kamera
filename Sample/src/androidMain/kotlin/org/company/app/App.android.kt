package org.company.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.company.app.devicematrix.DeviceMatrixApp
import org.company.app.devicematrix.MatrixArgs
import org.company.app.devicematrix.parseSections

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // adb shell am start -n org.company.app.androidApp/org.company.app.AppActivity --ez camerak.matrix true
        //   [--ei camerak.matrix.offset N] [--es camerak.matrix.sections photo,video] [--ei camerak.matrix.limit N]
        if (intent.getBooleanExtra("camerak.matrix", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val args = MatrixArgs(
                offset = intent.getIntExtra("camerak.matrix.offset", 0),
                sections = parseSections(intent.getStringExtra("camerak.matrix.sections")),
                limit = intent.getIntExtra("camerak.matrix.limit", 0).takeIf { it > 0 },
            )
            setContent { DeviceMatrixApp(args) }
        } else {
            setContent { App() }
        }
    }
}

@Preview
@Composable
fun AppPreview() {
    App()
}
