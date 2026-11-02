package com.kashif.cameraK.controller

import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue

/**
 * A [PhotosLibrary] that records each import and answers it as [answer] says, on a background
 * queue as Photos does, so tests decide whether an import succeeds, fails, or never finishes.
 */
internal class FakePhotosLibrary(private val answer: Answer = Answer.Imported("FAKE-ASSET/L0/001")) : PhotosLibrary {
    sealed interface Answer {
        data class Imported(val localIdentifier: String) : Answer

        data class Failed(val error: NSError) : Answer

        /** Photos never calls the completion, as while its permission prompt goes unanswered. */
        data object Never : Answer
    }

    class Import(val kind: String, val url: NSURL, val completion: (String?, NSError?) -> Unit)

    private val lock = NSLock()
    private val recorded = mutableListOf<Import>()

    val imports: List<Import>
        get() {
            lock.lock()
            try {
                return recorded.toList()
            } finally {
                lock.unlock()
            }
        }

    override fun importImage(fileUrl: NSURL, completion: (String?, NSError?) -> Unit) =
        record(Import("image", fileUrl, completion))

    override fun importVideo(fileUrl: NSURL, completion: (String?, NSError?) -> Unit) =
        record(Import("video", fileUrl, completion))

    private fun record(import: Import) {
        lock.lock()
        try {
            recorded += import
        } finally {
            lock.unlock()
        }
        val reply: (() -> Unit)? = when (val a = answer) {
            is Answer.Imported -> ({ import.completion(a.localIdentifier, null) })
            is Answer.Failed -> ({ import.completion(null, a.error) })
            Answer.Never -> null
        }
        reply?.let { dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), it) }
    }
}

/** An error as Photos reports a refused write (PHPhotosErrorAccessUserDenied). */
internal fun photosError(code: Long = 3311) = NSError.errorWithDomain("PHPhotosErrorDomain", code, null)
