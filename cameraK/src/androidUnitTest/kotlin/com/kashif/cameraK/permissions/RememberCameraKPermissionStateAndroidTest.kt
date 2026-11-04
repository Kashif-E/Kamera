package com.kashif.cameraK.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.utils.InternalCameraKApi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Android's [rememberCameraKPermissionState] in a composition: the Activity Result launcher's answer
 * reaches the permission record and the state, every resume reads the statuses again, and a
 * permission the API level does not need launches no prompt.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(InternalCameraKApi::class)
class RememberCameraKPermissionStateAndroidTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity: ComponentActivity = controller.get()
    private val registry = RecordingRegistry()
    private val results = CopyOnWriteArrayList<Map<CameraKPermission, PermissionStatus>>()

    /** Records each launch instead of starting the system's permission activity; the test answers it. */
    private class RecordingRegistry : ActivityResultRegistry() {
        val launches = CopyOnWriteArrayList<Pair<Int, List<String>>>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to (input as Array<*>).map { it as String }
        }

        fun answer(launch: Int, results: Map<String, Boolean>) {
            check(dispatchResult(launches[launch].first, results)) { "no launcher took the result" }
        }
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun rationale(shown: Boolean) {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, shown)
    }

    private fun compose(vararg permissions: CameraKPermission, context: Context = activity): CameraKPermissionState {
        var state: CameraKPermissionState? = null
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        activity.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalContext provides context,
            ) {
                state = rememberCameraKPermissionState(*permissions) { results += it }
            }
        }
        awaitMain("the first composition") { state != null }
        return state!!
    }

    /** Runs the main looper, where composition and the results run, until [condition] holds. */
    private fun awaitMain(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            if (System.nanoTime() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun aRequestResultIsRecordedAndReflected() {
        val state = compose(CameraKPermission.CAMERA)
        awaitMain("the first read") { state.statuses[CameraKPermission.CAMERA] == PermissionStatus.NotDetermined }

        rationale(shown = true)
        state.launchRequest()
        assertEquals(listOf(Manifest.permission.CAMERA), registry.launches.single().second)
        registry.answer(0, mapOf(Manifest.permission.CAMERA to false))
        awaitMain("the first result") { results.size == 1 }

        val denied = PermissionStatus.Denied(shouldShowRationale = true)
        assertEquals(mapOf(CameraKPermission.CAMERA to denied), results[0])
        assertEquals(denied, state.statuses[CameraKPermission.CAMERA])

        // Denied again with no rationale, after an earlier request: denied for good.
        rationale(shown = false)
        state.launchRequest()
        registry.answer(1, mapOf(Manifest.permission.CAMERA to false))
        awaitMain("the second result") { results.size == 2 }

        assertEquals(mapOf(CameraKPermission.CAMERA to PermissionStatus.PermanentlyDenied), results[1])
        assertEquals(PermissionStatus.PermanentlyDenied, state.statuses[CameraKPermission.CAMERA])
    }

    @Test
    fun everyResumeReadsTheStatusesAgain() {
        val state = compose(CameraKPermission.CAMERA)
        awaitMain("the first read") { state.statuses[CameraKPermission.CAMERA] == PermissionStatus.NotDetermined }

        // Granted in Settings while the app was in the background.
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        awaitMain("a frame") { true }
        assertEquals(PermissionStatus.NotDetermined, state.statuses[CameraKPermission.CAMERA], "read without a resume")

        controller.pause().resume()

        awaitMain("the read on resume") { state.statuses[CameraKPermission.CAMERA] == PermissionStatus.Granted }
    }

    @Test
    @Config(sdk = [29, 35])
    fun galleryAddFromApi29LaunchesNoPrompt() {
        val gallery = compose(CameraKPermission.GALLERY_ADD)
        gallery.launchRequest()
        awaitMain("the result") { results.size == 1 }

        assertEquals(emptyList(), registry.launches.toList(), "launched a prompt for the gallery")
        assertEquals(mapOf(CameraKPermission.GALLERY_ADD to PermissionStatus.Granted), results[0])
        assertEquals(PermissionStatus.Granted, gallery.statuses[CameraKPermission.GALLERY_ADD])

        val both = compose(CameraKPermission.CAMERA, CameraKPermission.GALLERY_ADD)
        both.launchRequest()

        assertEquals(listOf(Manifest.permission.CAMERA), registry.launches.single().second, "the launch's permissions")
    }

    @Test
    fun readsThePermissionRecordOffTheMainLooper() {
        // A denial recorded by an earlier session: reading it loads the record's preferences file.
        val record = PermissionRequestRecord(app)
        record.recordResult(Manifest.permission.CAMERA, granted = false, shouldShowRationale = true)
        record.recordResult(Manifest.permission.CAMERA, granted = false, shouldShowRationale = false)
        val reads = CopyOnWriteArrayList<Boolean>()
        val application = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(name, mode).also { reads += Looper.myLooper() == Looper.getMainLooper() }
        }
        val context = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = application
        }

        // The first read, a request and its result, and a resume: each reads the record.
        val state = compose(CameraKPermission.CAMERA, context = context)
        awaitMain("the first read") { state.statuses[CameraKPermission.CAMERA] == PermissionStatus.PermanentlyDenied }
        state.launchRequest()
        registry.answer(0, mapOf(Manifest.permission.CAMERA to false))
        awaitMain("the result") { results.size == 1 }
        val beforeResume = reads.size
        controller.pause().resume()
        awaitMain("the read on resume") { reads.size > beforeResume }

        assertEquals(PermissionStatus.PermanentlyDenied, state.statuses[CameraKPermission.CAMERA])
        val onMain = reads.count { it }
        assertEquals(0, onMain, "read the permission record on the main looper $onMain of ${reads.size} times")
    }
}
