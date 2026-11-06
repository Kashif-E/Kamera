package com.kashif.imagesaverplugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every save reads the gallery status first where the platform needs a permission, and a refused
 * save is `Permission(GALLERY_ADD, status)` without any gallery call.
 */
class GalleryPermissionGateTest : SaverTest() {
    private val image = FakeCaptures.image()

    private fun permissionStore(addPrompts: Boolean = false) =
        FakeGalleryStore(galleryPermission = CameraKPermission.GALLERY_ADD, addPrompts = addPrompts)

    @Test
    fun grantedAndLimitedProceed() = runTest {
        for (status in listOf(PermissionStatus.Granted, PermissionStatus.Limited)) {
            val store = permissionStore()

            val result = saver(store = store, permission = ScriptedPermission(status)).save(image)

            assertIs<CameraKResult.Success<SavedImage>>(result, "a save at $status")
            assertEquals(1, store.adds.size, "a save at $status did not reach the gallery")
        }
    }

    @Test
    fun permanentlyDeniedAndRestrictedFailWithPermissionAndWriteNothing() = runTest {
        for (status in listOf(PermissionStatus.PermanentlyDenied, PermissionStatus.Restricted)) {
            assertRefused(status, permissionStore())
            assertRefused(status, permissionStore(addPrompts = true))
        }
    }

    @Test
    fun deniedFailsWithPermissionAndWritesNothing() = runTest {
        assertRefused(PermissionStatus.Denied(shouldShowRationale = true), permissionStore())
    }

    @Test
    fun notDeterminedFailsWhereTheAddCannotPrompt() = runTest {
        assertRefused(PermissionStatus.NotDetermined, permissionStore(addPrompts = false))
    }

    @Test
    fun noPermissionNeededMeansTheStatusIsNeverRead() = runTest {
        val permission = ScriptedPermission(PermissionStatus.PermanentlyDenied)

        val result = saver(store = FakeGalleryStore(galleryPermission = null), permission = permission).save(image)

        assertIs<CameraKResult.Success<SavedImage>>(result)
        assertEquals(0, permission.reads, "the status was read where no permission is needed")
    }

    @Test
    fun autoSaveFailureLeavesTheCaptureUntouched() = runTest {
        val store = permissionStore()
        val plugin = saver(
            MediaSaverConfig { autoSave = true },
            store,
            ScriptedPermission(PermissionStatus.Denied(shouldShowRationale = false)),
        )
        val results = mutableListOf<CameraKResult<SavedImage>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { plugin.results.collect { results += it } }
        val s = saverSession(plugin)
        startReady(s)

        val captured = s.holder.capture()
        runCurrent()

        val error = assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(results.single()).error)
        assertEquals(CameraKPermission.GALLERY_ADD, error.permission)
        assertEquals(0, store.adds.size, "a refused auto-save reached the gallery")
        assertEquals(CameraKResult.Success(image), captured, "the capture itself changed")
    }

    private suspend fun assertRefused(status: PermissionStatus, store: FakeGalleryStore) {
        val result = saver(store = store, permission = ScriptedPermission(status)).save(image)

        val error =
            assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(result, "a save at $status").error)
        assertEquals(CameraKPermission.GALLERY_ADD, error.permission)
        assertEquals(status, error.status)
        assertEquals(0, store.adds.size, "a save at $status reached the gallery")
    }
}
