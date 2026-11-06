package com.kashif.imagesaverplugin

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.function.Supplier
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * MediaStore can refuse a save with exceptions other than IOException: SecurityException without
 * WRITE_EXTERNAL_STORAGE below API 29, IllegalArgumentException for a rejected insert. They must not
 * escape the save (the auto-save coroutine would crash the host), and a row inserted as IS_PENDING
 * must not be left behind.
 *
 * These cases fake the API 29+ MediaStore (VOLUME_EXTERNAL_PRIMARY, IS_PENDING), so they run at 35
 * only; [AndroidImageSaverPluginDirectoryTest] covers the save below API 29, down to minSdk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidImageSaverPluginFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val plugin = androidSaver(context)
    private val firstRow = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        1,
    )

    @Test
    fun save_whenTheWriteIsRefused_reportsFailedWithTheCause() = runTest {
        val refusal = SecurityException("no write access")
        writesTo(firstRow) { throw refusal }

        val result = plugin.save(captured(byteArrayOf(1, 2, 3)), "refused")

        assertSame(refusal, assertIs<CameraKResult.Failure>(result).error.cause)
        assertEquals(emptyMap(), mediaStore.rows, "the IS_PENDING row was left in MediaStore")
    }

    @Test
    fun save_whenTheInsertIsRejected_reportsFailedWithTheCause() = runTest {
        val rejection = IllegalArgumentException("unknown collection")
        mediaStore.insertFailure = rejection

        val result = plugin.save(captured(byteArrayOf(1, 2, 3)), "rejected")

        assertSame(rejection, assertIs<CameraKResult.Failure>(result).error.cause)
    }

    @Test
    fun save_writesTheBytesAndPublishesTheRow() = runTest {
        val written = ByteArrayOutputStream()
        writesTo(firstRow) { written }
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 42)

        val result = plugin.save(captured(bytes), "ok")

        assertEquals(firstRow, savedUri(result))
        assertContentEquals(bytes, written.toByteArray())
        assertEquals(0, mediaStore.rows.getValue(firstRow).getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    private fun writesTo(uri: Uri, open: () -> OutputStream) {
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri, Supplier { open() })
    }
}

/**
 * Stores MediaStore rows in memory so a test can see what a save left behind.
 *
 * Like MediaProvider from API 29 (`ensureFileColumns`), it rejects a row whose RELATIVE_PATH starts
 * outside the collection's primary directories: `Images` takes `DCIM/` and `Pictures/`, `Video`
 * takes `DCIM/`, `Movies/` and `Pictures/`, `Files` takes `Download/` and `Documents/`.
 */
class FakeMediaStore : ContentProvider() {
    val rows = linkedMapOf<Uri, ContentValues>()
    var insertFailure: RuntimeException? = null

    /** Runs on the inserting thread before the row is stored, outside the provider's lock. */
    @Volatile
    var beforeInsert: (() -> Unit)? = null
    private var nextId = 1L

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        beforeInsert?.invoke()
        return synchronized(this) { store(uri, values) }
    }

    private fun store(uri: Uri, values: ContentValues?): Uri {
        insertFailure?.let { throw it }
        values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.let { relativePath ->
            val primary = relativePath.substringBefore('/')
            val allowed = allowedPrimaryDirectories(uri)
            require(allowed.any { it.equals(primary, ignoreCase = true) }) {
                "Primary directory $primary not allowed for $uri; allowed directories are $allowed"
            }
        }
        val row = ContentUris.withAppendedId(uri, nextId++)
        rows[row] = ContentValues(values ?: ContentValues())
        return row
    }

    @Synchronized
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows[uri] ?: return 0
        values?.let(row::putAll)
        return 1
    }

    @Synchronized
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (rows.remove(uri) != null) 1 else 0

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    private fun allowedPrimaryDirectories(collection: Uri): List<String> = when (collection.pathSegments.getOrNull(1)) {
        "images" -> listOf("DCIM", "Pictures")
        "video" -> listOf("DCIM", "Movies", "Pictures")
        "file" -> listOf("Download", "Documents")
        else -> error("FakeMediaStore does not model $collection")
    }
}
