package com.kashif.cameraK.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class AndroidContextTest {
    @Test
    fun getActivityOrNull_unwrapsNestedContextWrappersToTheActivity() {
        Robolectric.buildActivity(Activity::class.java).use { controller ->
            val activity = controller.setup().get()
            val wrapped = ContextWrapper(ContextWrapper(activity))

            assertSame(activity, wrapped.getActivityOrNull())
        }
    }

    @Test
    fun getActivityOrNull_returnsNullForTheApplicationContext() {
        assertNull(ApplicationProvider.getApplicationContext<Context>().getActivityOrNull())
    }

    @Test
    fun appContextInitializer_makesTheApplicationContextAvailable() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        AppContextInitializer().create(application)

        assertSame(application, AppContext.get())
    }
}
