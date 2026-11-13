package org.company.app.smoke

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.compose.CameraPreview
import com.kashif.cameraK.compose.rememberCameraKStateHolder
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.rememberCameraKPermissionState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What a smoke run expects the camera to reach: Ready with a photo, or NoCamera on a machine without one. */
internal enum class SmokeExpectation { READY, NO_CAMERA }

internal data class SmokeArgs(val expect: SmokeExpectation, val timeoutSeconds: Int)

/**
 * The smoke run [arguments] ask for, or null without `-cameraKSmoke`:
 * `-cameraKSmoke [-cameraKSmokeExpect ready|nocamera] [-cameraKSmokeTimeout seconds]`.
 */
internal fun smokeArgs(arguments: List<String>): SmokeArgs? {
    if ("-cameraKSmoke" !in arguments) return null
    fun valueOf(flag: String): String? = arguments.indexOf(flag).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    val expect = if (valueOf("-cameraKSmokeExpect") ==
        "nocamera"
    ) {
        SmokeExpectation.NO_CAMERA
    } else {
        SmokeExpectation.READY
    }
    return SmokeArgs(expect, valueOf("-cameraKSmokeTimeout")?.toIntOrNull() ?: DEFAULT_TIMEOUT_SECONDS)
}

internal class SmokeVerdict(val passed: Boolean, val line: String)

/**
 * Whether the run passed: the camera reached [state] in time ([state] null when it did not), and when
 * Ready, [photo] succeeded and its file holds [photoBytes] bytes.
 */
internal fun smokeVerdict(
    expect: SmokeExpectation,
    state: CameraKState?,
    photo: CameraKResult<CapturedImage>?,
    photoBytes: Long?,
): SmokeVerdict {
    val reached = when (state) {
        null -> "no Ready or Error in time"
        is CameraKState.Error -> "Error(${state.error})"
        else -> state.toString()
    }
    val passed = when (expect) {
        SmokeExpectation.READY ->
            state == CameraKState.Ready && photo is CameraKResult.Success && (photoBytes ?: 0) > 0
        SmokeExpectation.NO_CAMERA ->
            state is CameraKState.Error && state.error is CameraKError.NoCamera
    }
    val captured = when (photo) {
        null -> ""
        is CameraKResult.Success -> " photo=${photo.value.width}x${photo.value.height} bytes=$photoBytes"
        is CameraKResult.Failure -> " photo failed: ${photo.error}"
    }
    return SmokeVerdict(passed, "${if (passed) "PASS" else "FAIL"} expect=$expect state=$reached$captured")
}

/**
 * Opens the camera in the Sample's window with its preview, asking for the camera permission as the
 * Sample does, waits for Ready or Error, takes one photo when Ready, prints each state and the verdict
 * as `CameraKSmoke:` lines and reports the exit code to [onDone].
 */
@Composable
internal fun SmokeApp(args: SmokeArgs, onDone: (exitCode: Int) -> Unit) {
    val permission = rememberCameraKPermissionState(CameraKPermission.CAMERA) {
        println("CameraKSmoke: permission $it")
    }
    LaunchedEffect(Unit) {
        println("CameraKSmoke: permission ${permission.statuses}")
        if (!permission.allGranted) permission.launchRequest()
    }
    val holder = rememberCameraKStateHolder()
    CameraPreview(holder = holder, modifier = Modifier.fillMaxSize())
    LaunchedEffect(holder) {
        holder.cameraState.collect { println("CameraKSmoke: state $it") }
    }
    LaunchedEffect(holder) {
        val state = withTimeoutOrNull(args.timeoutSeconds * MILLIS_PER_SECOND) {
            holder.cameraState.first { it == CameraKState.Ready || it is CameraKState.Error }
        }
        println("CameraKSmoke: cameras=${holder.capabilities.value.allLenses.map { it.id }}")
        val photo = if (state == CameraKState.Ready) holder.capture() else null
        val bytes = (photo as? CameraKResult.Success)?.value?.path?.let { File(it).length() }
        val verdict = smokeVerdict(args.expect, state, photo, bytes)
        println("CameraKSmoke: ${verdict.line}")
        (photo as? CameraKResult.Success)?.value?.path?.let { File(it).delete() }
        onDone(if (verdict.passed) 0 else 1)
    }
}

private const val DEFAULT_TIMEOUT_SECONDS = 60
private const val MILLIS_PER_SECOND = 1_000L
