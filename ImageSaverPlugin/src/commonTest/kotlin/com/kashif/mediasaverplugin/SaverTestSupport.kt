@file:OptIn(CameraKEngineApi::class)

package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertIs

/**
 * The base of the saver's common tests. On Android it runs them under Robolectric, because a
 * [GalleryAsset] there wraps an `android.net.Uri`; elsewhere it is empty.
 */
expect abstract class SaverTest()

/** A gallery handle for a fake store; nothing on disk or in a gallery. */
internal expect fun fakeGalleryAsset(displayName: String): GalleryAsset

/** One call a [FakeGalleryStore] received. */
internal class StoreAdd(val path: String?, val kind: MediaKind, val format: ImageFormat?, val name: String?)

/** A platform gallery that records what it is asked to add and answers with [answer]. */
internal class FakeGalleryStore(
    override val galleryPermission: CameraKPermission? = null,
    override val addPrompts: Boolean = false,
) : GalleryStore {
    val adds = mutableListOf<StoreAdd>()
    var answer: (StoreAdd) -> CameraKResult<GalleryAsset> = { add ->
        CameraKResult.Success(fakeGalleryAsset("${add.name ?: "IMG"}.${add.format?.extension ?: "mp4"}"))
    }

    override suspend fun addImage(path: String, format: ImageFormat, name: String?): CameraKResult<GalleryAsset> {
        val add = StoreAdd(path, MediaKind.IMAGE, format, name)
        adds += add
        return answer(add)
    }

    override suspend fun addVideo(path: String, name: String?): CameraKResult<GalleryAsset> {
        val add = StoreAdd(path, MediaKind.VIDEO, null, name)
        adds += add
        return answer(add)
    }

    override suspend fun read(asset: GalleryAsset): CameraKResult<ByteArray> = CameraKResult.Success(ByteArray(0))
}

/** A permission read that answers each status in [statuses] in turn, then the last one, and counts its reads. */
internal class ScriptedPermission(vararg statuses: PermissionStatus) : GalleryPermissionRead {
    private val script = statuses.toList()
    var reads = 0

    override fun status(): PermissionStatus = script[minOf(reads++, script.lastIndex)]
}

internal const val FIXED_MILLIS = 1_700_000_000_000L

internal fun saver(
    config: MediaSaverConfig = MediaSaverConfig(),
    store: GalleryStore = FakeGalleryStore(),
    permission: GalleryPermissionRead = ScriptedPermission(PermissionStatus.Granted),
): ImageSaverPlugin = ImageSaverPlugin(config, store, permission) { FIXED_MILLIS }

/** A holder over a fake engine with [plugin] attached at creation, the config its factory got, and its events. */
internal class SaverSession(
    val holder: CameraKStateHolder,
    val engine: FakeCameraEngine,
    val events: List<CameraKEvent>,
    val engineConfig: () -> CameraConfiguration?,
)

internal fun TestScope.saverSession(plugin: ImageSaverPlugin): SaverSession {
    val engine = FakeCameraEngine()
    var created: CameraConfiguration? = null
    val holder = CameraKStateHolder(
        CameraConfiguration(),
        { config, _ -> engine.also { created = config } },
        listOf(plugin),
        cameraKTestEnvironment(),
    )
    val events = mutableListOf<CameraKEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
    return SaverSession(holder, engine, events) { created }
}

internal fun TestScope.startReady(session: SaverSession) {
    session.holder.start()
    runCurrent()
    assertIs<CameraKState.Ready>(session.holder.cameraState.value, "state is ${session.holder.cameraState.value}")
}
