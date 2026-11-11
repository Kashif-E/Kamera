package org.company.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.permissions.rememberCameraKPermissionState
import kotlinx.coroutines.launch
import org.company.app.designsystem.SampleSnackbarHost
import org.company.app.designsystem.SampleTheme

@Composable
fun App() = SampleTheme {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saveToGallery by rememberSaveable { mutableStateOf(true) }
    var ocrAccuracy by rememberSaveable { mutableStateOf(OcrAccuracy.Fast) }
    val imageSaver = rememberImageSaverFeature(saveToGallery)
    val analyzer = rememberAnalyzerFeature()
    val qrScanner = rememberQrScannerFeature()
    val ocr = rememberOcrFeature(ocrAccuracy)

    val asked = remember(imageSaver) { samplePermissions(imageSaver.plugin.config.autoSave) }
    val permissions = rememberCameraKPermissionState(*asked.toTypedArray()) { results ->
        permissionNotice(results)?.let { scope.launch { snackbarHostState.showSnackbar(it) } }
    }
    AskWhenOpened(asked = asked, allGranted = permissions.allGranted, launchRequest = permissions::launchRequest)

    Scaffold(
        snackbarHost = { SampleSnackbarHost(snackbarHostState) },
        // The ground colour runs under the system bars, so their dark icons sit on it.
        modifier = Modifier.background(SampleTheme.colors.background).windowInsetsPadding(WindowInsets.systemBars),
        containerColor = SampleTheme.colors.background,
    ) { padding ->
        val cameraStatus = permissions.statuses[CameraKPermission.CAMERA]
        if (cameraGranted(cameraStatus)) {
            CameraContent(
                analyzer = analyzer,
                imageSaver = imageSaver,
                qrScanner = qrScanner,
                ocr = ocr,
                snackbarHostState = snackbarHostState,
                options = SampleOptions(
                    saveToGallery = saveToGallery,
                    onSaveToGalleryChange = { saveToGallery = it },
                    ocrAccuracy = ocrAccuracy,
                    onOcrAccuracyChange = { ocrAccuracy = it },
                ),
            )
        } else {
            PermissionScreen(
                cameraStatus = cameraStatus,
                onAllow = permissions::launchRequest,
                onOpenSettings = rememberAppSettingsOpener(),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/**
 * Asks for [asked] when the app opens, as the 1.x screen did, and again when Save to gallery adds the
 * gallery to it. A preview asks for nothing.
 *
 * What was asked is saved: a rotation recreates the screen, and asking again there for a permission
 * just refused would show the dialog on every rotation. The permission screen's button still asks.
 */
@Composable
internal fun AskWhenOpened(asked: List<CameraKPermission>, allGranted: Boolean, launchRequest: () -> Unit) {
    val inspecting = LocalInspectionMode.current
    val granted by rememberUpdatedState(allGranted)
    // Names joined in one string, which every platform's saved state holds.
    var askedBefore by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(asked) {
        val before = askedBefore.split(',').toSet()
        if (inspecting || granted || asked.all { it.name in before }) return@LaunchedEffect
        askedBefore = (before + asked.map { it.name }).filter { it.isNotEmpty() }.joinToString(",")
        launchRequest()
    }
}

/**
 * What the Sample asks for when it opens: the camera, the microphone its recordings use, and the
 * gallery only when the image saver puts every capture there. On Android 10 and later and on desktop the gallery needs no permission and reads
 * Granted.
 */
internal fun samplePermissions(autoSave: Boolean): List<CameraKPermission> =
    listOf(CameraKPermission.CAMERA, CameraKPermission.MICROPHONE) +
        if (autoSave) listOf(CameraKPermission.GALLERY_ADD) else emptyList()

/** Whether the Sample shows the camera for this camera permission status; Limited counts as granted. */
internal fun cameraGranted(status: PermissionStatus?): Boolean =
    status == PermissionStatus.Granted || status == PermissionStatus.Limited

/**
 * The line the screen shows when the camera was refused; a refused gallery only stops the saving, and a
 * camera not asked yet is no refusal.
 */
internal fun permissionNotice(results: Map<CameraKPermission, PermissionStatus>): String? =
    when (results[CameraKPermission.CAMERA]) {
        null, PermissionStatus.Granted, PermissionStatus.Limited, PermissionStatus.NotDetermined -> null
        PermissionStatus.PermanentlyDenied -> "Allow camera access in Settings"
        PermissionStatus.Restricted -> "Camera access is restricted on this device"
        is PermissionStatus.Denied -> "Camera access was denied"
    }
