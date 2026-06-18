package com.kashif.imagesaverplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Configuration for the ImageSaverPlugin.
 *
 * @param isAutoSave Determines if images should be saved automatically upon capture.
 * @param prefix Optional prefix for image filenames when auto-saving.
 * @param directory Specifies the directory where images will be saved when auto-saving.
 * @param customFolderName Optional custom folder name within the specified directory.
 */
@Immutable
data class ImageSaverConfig(
    val isAutoSave: Boolean = false,
    val prefix: String? = null,
    val directory: Directory = Directory.PICTURES,
    val customFolderName: String? = null,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
)

/**
 * Abstract plugin for saving captured images to device storage.
 *
 * Implements [CameraKPlugin] for Compose-first state management.
 * Provides automatic and manual image saving with configurable naming and directory options.
 *
 * @param config Configuration settings for image saving behavior.
 *
 * @property config Immutable configuration for saving behavior.
 *
 * @example
 * ```kotlin
 * val plugin = rememberImageSaverPlugin(
 *     config = ImageSaverConfig(
 *         isAutoSave = true,
 *         prefix = "MyApp",
 *         directory = Directory.PICTURES,
 *         customFolderName = "MyPhotos"
 *     )
 * )
 *
 * val cameraState by rememberCameraKState { stateHolder ->
 *     plugin.onAttach(stateHolder)
 * }
 * ```
 */
@Stable
abstract class ImageSaverPlugin(val config: ImageSaverConfig) : CameraKPlugin {
    private var stateHolder: CameraKStateHolder? = null
    private var autoSaveJob: Job? = null

    // The controller the capture listener is currently registered on, so we can remove it on
    // re-init / detach instead of stacking listeners (which would save each capture N times).
    private var registeredController: CameraController? = null

    // Stable reference so addImageCaptureListener / removeImageCaptureListener target the same lambda.
    @OptIn(ExperimentalTime::class)
    private val captureListener: (ByteArray) -> Unit = { byteArray ->
        stateHolder?.pluginScope?.launch(Dispatchers.IO) {
            val imageName = config.prefix?.let { "${it}_${Clock.System.now().toEpochMilliseconds()}" }
            saveImage(byteArray, imageName)
        }
    }

    /**
     * Saves the captured image data to storage.
     *
     * @param byteArray The image data as a [ByteArray].
     * @param imageName Optional custom name for the image. If not provided, a default name is generated.
     * @return The file path where the image was saved, or null if saving failed.
     */
    abstract suspend fun saveImage(byteArray: ByteArray, imageName: String? = null): String?

    /**
     * Attaches the plugin to the state holder (new API).
     * If auto-save is enabled, automatically saves images when camera becomes ready.
     *
     * @param stateHolder The [CameraKStateHolder] to attach to.
     */
    override fun onAttach(stateHolder: CameraKStateHolder) {
        this.stateHolder = stateHolder

        if (!config.isAutoSave) return

        // Re-register on every Ready (a lens switch / re-init produces a new controller), moving
        // the single listener off the previous controller so it's never registered twice.
        autoSaveJob?.cancel()
        autoSaveJob = stateHolder.pluginScope.launch {
            stateHolder.cameraState.filterIsInstance<CameraKState.Ready>().collect { ready ->
                registeredController?.removeImageCaptureListener(captureListener)
                ready.controller.addImageCaptureListener(captureListener)
                registeredController = ready.controller
            }
        }
    }

    /**
     * Detaches the plugin from the state holder and cleans up resources.
     */
    override fun onDetach() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        registeredController?.removeImageCaptureListener(captureListener)
        registeredController = null
        stateHolder = null
    }

    /**
     * Retrieves the image byte array from a file path.
     *
     * @param path The file path to read.
     * @return The image data as a [ByteArray].
     */
    abstract fun getByteArrayFrom(path: String): ByteArray
}

/**
 * Creates a remembered [ImageSaverPlugin] instance with automatic lifecycle management.
 *
 * @param config Configuration settings for image saving.
 * @param context Platform context for accessing device storage.
 * @return An instance of platform-specific [ImageSaverPlugin].
 *
 * @example
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val imageSaver = rememberImageSaverPlugin(
 *         config = ImageSaverConfig(isAutoSave = true)
 *     )
 * }
 * ```
 */
@Composable
fun rememberImageSaverPlugin(
    config: ImageSaverConfig,
    context: PlatformContext = LocalPlatformContext.current,
): ImageSaverPlugin = remember(config) {
    createPlatformImageSaverPlugin(context, config)
}

/**
 * Platform-specific factory for creating [ImageSaverPlugin] instances.
 *
 * Must be implemented for each platform (Android, iOS, Desktop).
 *
 * @param context Platform context for storage operations.
 * @param config Configuration settings for the plugin.
 * @return An instance of [ImageSaverPlugin] for the current platform.
 */
expect fun createPlatformImageSaverPlugin(context: PlatformContext, config: ImageSaverConfig): ImageSaverPlugin
