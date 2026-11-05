@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.imagesaverplugin

import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Photos.PHImageRequestID
import platform.Photos.PHImageRequestOptions
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * A stand-in for PhotoKit, so the simulator needs no photo-library permission. It follows
 * PHImageManager's documented contract: a synchronous request answers once on the calling thread
 * before it returns and cannot be cancelled; an asynchronous one answers once, later, on the main
 * thread. Imports and resource writes answer on a background queue, as Photos does.
 */
internal class FakePhotoLibrary(
    private val assetExists: Boolean = true,
    private val imageData: NSData? = null,
    private val imageDataUTI: String = "public.jpeg",
    private val createdIdentifier: String? = ASSET_ID,
    private val createError: NSError? = null,
    private val completesCreation: Boolean = true,
    private val answersReads: Boolean = true,
    private val resourceBytes: ByteArray? = null,
    private val resourceError: NSError? = null,
) : PhotoLibraryGateway {
    class Import(val url: NSURL, val kind: MediaKind)

    val requestedIdentifier = atomic<String?>(null)
    val createdOnMainThread = atomic<Boolean?>(null)
    val wroteOnMainThread = atomic<Boolean?>(null)
    val writtenTo = atomic<String?>(null)
    val imports = atomic(emptyList<Import>())

    /** The bytes and type of the photo resource stored byte for byte, if one was. */
    val storedPhoto = atomic<Pair<ByteArray, String>?>(null)
    val creationStarted = CompletableDeferred<Unit>()
    val readStarted = CompletableDeferred<PHImageRequestID>()
    val cancelledRequests = atomic(emptyList<PHImageRequestID>())
    private val nextRequestId = atomic(1)

    // A synchronous read Photos has not answered blocks its caller, as PhotoKit does, until the test ends.
    private val blockedReads = dispatch_semaphore_create(0)

    fun answerBlockedReads() {
        repeat(4) { dispatch_semaphore_signal(blockedReads) }
    }

    override fun createAsset(fileUrl: NSURL, kind: MediaKind, completion: (String?, NSError?) -> Unit) {
        imports.update { it + Import(fileUrl, kind) }
        create(completion)
    }

    override fun createPhotoAsset(
        data: NSData,
        uniformTypeIdentifier: String,
        completion: (String?, NSError?) -> Unit,
    ) {
        storedPhoto.value = data.toByteArray() to uniformTypeIdentifier
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
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            if (error == null && bytes != null && path != null) {
                NSFileManager.defaultManager.createFileAtPath(path, bytes.toNSData(), null)
            }
            completion(error)
        }
        return true
    }

    override fun requestImageData(
        localIdentifier: String,
        options: PHImageRequestOptions,
        resultHandler: (NSData?, String?) -> Unit,
    ): PHImageRequestID? {
        requestedIdentifier.value = localIdentifier
        if (!assetExists) return null
        val requestId = nextRequestId.getAndIncrement()
        readStarted.complete(requestId)
        if (options.synchronous) {
            if (!answersReads) {
                dispatch_semaphore_wait(blockedReads, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC.toLong()))
            }
            val data = if (answersReads) imageData else null
            resultHandler(data, imageDataUTI.takeIf { data != null })
        } else if (answersReads) {
            val data = imageData
            val type = imageDataUTI.takeIf { data != null }
            dispatch_async(dispatch_get_main_queue()) { resultHandler(data, type) }
        }
        // An unanswered asynchronous read never calls back; after a cancel PhotoKit may not either.
        return requestId
    }

    override fun cancelImageRequest(requestId: PHImageRequestID) {
        cancelledRequests.update { it + requestId }
    }

    companion object {
        const val ASSET_ID = "4F1C2A3B-0000-4000-8000-000000000001/L0/001"
    }
}

/** An error as Photos reports a refused write (PHPhotosErrorAccessUserDenied by default). */
internal fun photosError(code: Long = 3311, description: String = "The operation couldn't be completed") =
    NSError.errorWithDomain("PHPhotosErrorDomain", code, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description))
