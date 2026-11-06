@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.imagesaverplugin

import com.kashif.cameraK.utils.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue

/**
 * A stand-in for PhotoKit, so the simulator needs no photo-library permission. Imports and resource
 * writes answer on a background queue, as Photos does.
 */
internal class FakePhotoLibrary(
    private val assetExists: Boolean = true,
    private val createdIdentifier: String? = ASSET_ID,
    private val createError: NSError? = null,
    private val completesCreation: Boolean = true,
    private val resourceBytes: ByteArray? = null,
    private val resourceError: NSError? = null,
    private val holdsResourceWrites: Boolean = false,
    private val afterResourceWrite: (path: String) -> Unit = {},
) : PhotoLibraryGateway {
    class Import(val url: NSURL, val kind: MediaKind)

    val requestedIdentifier = atomic<String?>(null)
    val createdOnMainThread = atomic<Boolean?>(null)
    val wroteOnMainThread = atomic<Boolean?>(null)
    val writtenTo = atomic<String?>(null)
    val imports = atomic(emptyList<Import>())

    val creationStarted = CompletableDeferred<Unit>()

    /** Completes with the file's path when a resource write starts, and once its completion has run. */
    val resourceWriteStarted = CompletableDeferred<String>()
    val resourceWriteFinished = CompletableDeferred<Unit>()
    private val heldWrite = atomic<(() -> Unit)?>(null)

    /** Lets a write held by `holdsResourceWrites` go ahead: Photos writes the file, then calls back. */
    fun releaseResourceWrite() {
        val write = checkNotNull(heldWrite.getAndSet(null)) { "no resource write is held" }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { write() }
    }
    override fun createAsset(fileUrl: NSURL, kind: MediaKind, completion: (String?, NSError?) -> Unit) {
        imports.update { it + Import(fileUrl, kind) }
        create(completion)
    }

    private fun create(completion: (String?, NSError?) -> Unit) {
        createdOnMainThread.value = NSThread.isMainThread
        creationStarted.complete(Unit)
        // Photos may call the completion before performChanges returns; the bridge must not lose it.
        if (completesCreation) completion(createdIdentifier, createError)
    }

    override fun writeAssetData(localIdentifier: String, fileUrl: NSURL, completion: (NSError?) -> Unit): Boolean {
        requestedIdentifier.value = localIdentifier
        if (!assetExists) return false
        wroteOnMainThread.value = NSThread.isMainThread
        val path = fileUrl.path
        writtenTo.value = path
        val bytes = resourceBytes
        val error = resourceError
        val write: () -> Unit = {
            if (error == null && bytes != null && path != null) {
                NSFileManager.defaultManager.createFileAtPath(path, bytes.toNSData(), null)
                afterResourceWrite(path)
            }
            completion(error)
            resourceWriteFinished.complete(Unit)
        }
        path?.let { resourceWriteStarted.complete(it) }
        // Photos cannot cancel a resource write; a held one finishes only when the test releases it.
        if (holdsResourceWrites) {
            heldWrite.value = write
        } else {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { write() }
        }
        return true
    }

    companion object {
        const val ASSET_ID = "4F1C2A3B-0000-4000-8000-000000000001/L0/001"
    }
}

/** An error as Photos reports a refused write (PHPhotosErrorAccessUserDenied by default). */
internal fun photosError(code: Long = 3311, description: String = "The operation couldn't be completed") =
    NSError.errorWithDomain("PHPhotosErrorDomain", code, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description))
