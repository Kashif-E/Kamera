package com.kashif.ocrplugin

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Hands a live recognizer to frames and closes it once, after the last frame using it returns. The
 * session that owns it asks for the close when it ends and never waits for a recognition in flight,
 * which can take a second on Tesseract; that recognition's frame closes it instead.
 */
internal class RecognizerLease(private val recognizer: LiveTextRecognizer) {
    private val lock = SynchronizedObject()
    private var inFlight = 0
    private var closing = false
    private var closed = false

    /** Runs [block] with the recognizer, or returns null once a close was asked for. */
    fun <T> use(block: (LiveTextRecognizer) -> T): T? {
        synchronized(lock) {
            if (closing) return null
            inFlight++
        }
        try {
            return block(recognizer)
        } finally {
            val closeNow = synchronized(lock) {
                inFlight--
                takeClose()
            }
            if (closeNow) close()
        }
    }

    /** Closes the recognizer now if no frame is using it, otherwise when the last one returns. */
    fun closeWhenIdle() {
        val closeNow = synchronized(lock) {
            closing = true
            takeClose()
        }
        if (closeNow) close()
    }

    // Under the lock: whether this caller is the one that closes.
    private fun takeClose(): Boolean {
        if (!closing || inFlight > 0 || closed) return false
        closed = true
        return true
    }

    private fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "releasing the live text recognizer failed", e)
        }
    }

    private companion object {
        const val TAG = "TextRecognition"
    }
}
