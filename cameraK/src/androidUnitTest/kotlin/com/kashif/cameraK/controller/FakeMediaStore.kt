package com.kashif.cameraK.controller

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.function.Supplier

/**
 * Keeps MediaStore rows in memory, with each row's bytes in a file of its own, so a test can see
 * which gallery entries a capture created and what they hold.
 *
 * Like MediaProvider from API 29 (`ensureFileColumns`), it rejects a row whose RELATIVE_PATH starts
 * outside the collection's primary directories: `Images` takes `DCIM/` and `Pictures/`.
 */
class FakeMediaStore : ContentProvider() {
    val rows = linkedMapOf<Uri, ContentValues>()
    private val contents = linkedMapOf<Uri, File>()
    private var nextId = 1L

    override fun onCreate() = true

    @Synchronized
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.let { relativePath ->
            val primary = relativePath.substringBefore('/')
            val allowed = allowedPrimaryDirectories(uri)
            require(allowed.any { it.equals(primary, ignoreCase = true) }) {
                "Primary directory $primary not allowed for $uri; allowed directories are $allowed"
            }
        }
        val row = ContentUris.withAppendedId(uri, nextId++)
        rows[row] = ContentValues(values ?: ContentValues())
        val content = File.createTempFile("mediastore-row", ".bin").apply { deleteOnExit() }
        contents[row] = content
        val resolver = shadowOf(requireNotNull(context).contentResolver)
        resolver.registerOutputStreamSupplier(row, Supplier { FileOutputStream(content) })
        resolver.registerInputStreamSupplier(row, Supplier { FileInputStream(content) })
        return row
    }

    /** The bytes written to [row]. */
    @Synchronized
    fun bytesOf(row: Uri): ByteArray = contents.getValue(row).readBytes()

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
        else -> error("FakeMediaStore does not model $collection")
    }
}
