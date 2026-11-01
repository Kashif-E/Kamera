package com.kashif.imagesaverplugin

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.util.function.Supplier
import kotlin.test.assertEquals

/**
 * Auto-save runs one save per capture and up to three captures can be in flight, so two saves without
 * a name in the same second must not be given the same default name.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidImageSaverPluginNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)

    @Test
    fun savesWithoutAName_getDistinctNames() = runTest {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val rows = listOf(1L, 2L).map { ContentUris.withAppendedId(collection, it) }
        rows.forEach {
            shadowOf(context.contentResolver).registerOutputStreamSupplier(it, Supplier { ByteArrayOutputStream() })
        }
        val plugin = AndroidImageSaverPlugin(context, ImageSaverConfig())

        repeat(2) { plugin.save(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)) }

        val names = rows.map { mediaStore.rows.getValue(it).getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }
        assertEquals(2, names.toSet().size, "both saves were named $names")
    }
}
