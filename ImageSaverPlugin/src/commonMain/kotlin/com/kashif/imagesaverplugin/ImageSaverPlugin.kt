package com.kashif.imagesaverplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.map
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Publishes captures and recordings to the photo gallery, the only CameraK code that writes there,
 * so one capture makes at most one gallery entry. Core writes app-owned files only; the saver copies
 * them, never decodes them, and never deletes or changes them.
 *
 * With [MediaSaverConfig.autoSave] every capture that reaches the holder's `captures` (each
 * successful `capture()`) is saved, and its outcome is emitted on [results]. A failed auto-save is a
 * `Failure` there, logged with its cause; the plugin carries on, and the next capture is saved again.
 *
 * Every save first reads the `GALLERY_ADD` status where the platform needs it ([galleryPermission])
 * and fails with `Permission(GALLERY_ADD, status)` without touching the gallery unless it is
 * Granted or Limited. iOS also proceeds at NotDetermined, because Photos raises its add-only prompt
 * during the save. The saver shows no prompt itself: the app requests [galleryPermission].
 *
 * @property config What and where the saver publishes.
 */
public class ImageSaverPlugin internal constructor(
    public val config: MediaSaverConfig,
    private val store: GalleryStore,
    private val permissionRead: GalleryPermissionRead,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CameraKPlugin {
    /** A saver for [config] that reaches the platform's gallery through [platformContext]. */
    public constructor(config: MediaSaverConfig, platformContext: CameraKPlatformContext) : this(
        config,
        platformGalleryStore(platformContext, config),
        platformGalleryPermissionRead(platformContext),
    )

    /** The saver reads captures, never analysis frames, so a holder created with it binds no frame analysis. */
    override val needsFrames: Boolean get() = false

    private val _status = MutableStateFlow<PluginStatus>(PluginStatus.Idle)

    /**
     * Running while attached with auto-save on, Idle otherwise, and `Failed` while the current
     * attachment's context has a failure: the plugin itself broke, not a save.
     */
    public val status: StateFlow<PluginStatus> = _status.asStateFlow()

    /**
     * What the platform needs before a save can add to the gallery: `GALLERY_ADD` on iOS and on
     * Android API 28 and below, null on Android API 29 and later and on desktop.
     */
    public val galleryPermission: CameraKPermission? get() = store.galleryPermission

    private val _results = MutableSharedFlow<CameraKResult<SavedImage>>(
        extraBufferCapacity = RESULTS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Each auto-save's outcome. A collector that falls behind loses the oldest outcomes first. */
    public val results: Flow<CameraKResult<SavedImage>> = _results.asSharedFlow()

    override fun onAttach(context: CameraKPluginContext) {
        context.scope.launch {
            context.failure.collect { failure ->
                _status.value = when {
                    failure != null -> PluginStatus.Failed(failure)
                    config.autoSave -> PluginStatus.Running
                    else -> PluginStatus.Idle
                }
            }
        }
        if (!config.autoSave) return
        context.scope.launch {
            context.captures.collect { image ->
                // One save per capture, so a slow gallery never holds back the next capture's save.
                launch(context.dispatchers.io) { autoSave(image) }
            }
        }
    }

    override fun onDetach() {
        _status.value = PluginStatus.Idle
    }

    private suspend fun autoSave(image: CapturedImage) {
        val name = config.prefix?.let { autoSaveImageName(it, clock()) }
        _results.emit(save(image, name))
    }

    /**
     * Copies [image]'s file into the gallery as [name] plus its format's extension, or under a name of
     * the platform's own when [name] is null. The file is read, never decoded or changed.
     *
     * @return the saved image; `Failure(Permission)` without a gallery write when the gallery
     *   permission is not granted; `Failure(Storage)` when the gallery refused it. On iOS a failed or
     *   timed-out import is `Storage(GALLERY_IMPORT_FAILED)` whose `keptFilePath` is [image]'s file.
     * @throws IllegalArgumentException when [name] is not 1 to 64 characters from
     *   `A-Z a-z 0-9 . _ -`, or is `.` or `..`. The message never repeats it.
     */
    public suspend fun save(image: CapturedImage, name: String? = null): CameraKResult<SavedImage> {
        name?.let { requireSafeFileName(it, "name") }
        return gated("Saving an image to the gallery") {
            store.addImage(image.path, image.format, name)
        }.map { SavedImage(it.displayName, it) }
    }

    /**
     * Copies [video]'s file into the gallery, as [save] does a still.
     *
     * @throws IllegalArgumentException for a [name] that breaks the same rule.
     */
    public suspend fun save(video: RecordedVideo, name: String? = null): CameraKResult<SavedVideo> {
        name?.let { requireSafeFileName(it, "name") }
        return gated("Saving a video to the gallery") { store.addVideo(video.path, name) }
            .map { SavedVideo(it.displayName, it) }
    }

    /** The bytes of an image this saver saved, read without blocking the calling thread. */
    public suspend fun readBytes(saved: SavedImage): CameraKResult<ByteArray> = store.read(saved.asset)

    // Reads the permission where the platform needs it, then adds; see the class KDoc for the rule.
    // Every failure is logged here once: a refusal by refused, a failed add by logged, never by a store.
    private suspend fun <T> gated(operation: String, add: suspend () -> CameraKResult<T>): CameraKResult<T> {
        if (store.galleryPermission == null) return logged(operation, add())
        val status = permissionRead.status()
        when {
            status == PermissionStatus.Granted || status == PermissionStatus.Limited -> return logged(operation, add())
            status != PermissionStatus.NotDetermined || !store.addPrompts -> return refused(status)
        }
        val result = logged(operation, add())
        if (result is CameraKResult.Failure) {
            val answered = permissionRead.status()
            if (answered == PermissionStatus.PermanentlyDenied || answered == PermissionStatus.Restricted) {
                return CameraKResult.Failure(
                    CameraKError.Permission(CameraKPermission.GALLERY_ADD, answered, result.error.cause),
                )
            }
        }
        return result
    }

    private fun <T> logged(operation: String, result: CameraKResult<T>): CameraKResult<T> {
        if (result is CameraKResult.Failure) {
            val error = result.error
            val kept = if (error is CameraKError.Storage && error.keptFilePath != null) "; the file is kept" else ""
            CameraKLogger.e(TAG, "$operation failed (${error::class.simpleName})$kept", error.cause)
        }
        return result
    }

    private fun refused(status: PermissionStatus): CameraKResult<Nothing> {
        CameraKLogger.w(TAG, "A save was refused: the gallery permission is $status")
        return CameraKResult.Failure(CameraKError.Permission(CameraKPermission.GALLERY_ADD, status))
    }

    /** Removed in 2.0. */
    @Deprecated(
        "Removed in CameraK 2.0: read a SavedImage with the suspending readBytes(saved).",
        ReplaceWith("readBytes(saved)"),
        level = DeprecationLevel.ERROR,
    )
    @Suppress("UNUSED_PARAMETER")
    public fun getByteArrayFrom(path: String): ByteArray =
        throw UnsupportedOperationException("Removed in CameraK 2.0; see the migration guide")

    private companion object {
        const val TAG = "ImageSaverPlugin"
        const val RESULTS_BUFFER = 16
    }
}

/** The name an auto-save gives a capture taken at [epochMillis]: [prefix], `_`, then the milliseconds. */
internal fun autoSaveImageName(prefix: String, epochMillis: Long): String = "${prefix}_$epochMillis"

/** The same configuration's saver across recompositions, over [platformContext]'s gallery. */
@Composable
public fun rememberImageSaverPlugin(
    config: MediaSaverConfig,
    platformContext: CameraKPlatformContext = rememberCameraKPlatformContext(),
): ImageSaverPlugin = remember(config, platformContext) { ImageSaverPlugin(config, platformContext) }

/** This platform's [CameraKPlatformContext], remembered: the application context on Android. */
@Composable
internal expect fun rememberCameraKPlatformContext(): CameraKPlatformContext
