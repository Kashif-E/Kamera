package com.kashif.ocrplugin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual fun platformTextRecognizers(config: OcrConfig): TextRecognizerFactory = AndroidTextRecognizers()

/**
 * The ML Kit calls OCR makes, so a test can stand in for ML Kit and check that every client it
 * creates is closed. [close] frees the client's native model handles. Each task fails with ML Kit's
 * exception.
 */
internal interface MlKitTextClient {
    fun recognize(bitmap: Bitmap, rotationDegrees: Int): Task<List<FoundBlock>>

    fun recognizeNv21(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): Task<List<FoundBlock>>

    fun close()
}

/** [MlKitTextClient] over ML Kit's Latin text recognizer: the bundled model, or the one Play services downloads. */
internal class LatinMlKitTextClient : MlKitTextClient {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(bitmap: Bitmap, rotationDegrees: Int): Task<List<FoundBlock>> =
        process(InputImage.fromBitmap(bitmap, rotationDegrees))

    override fun recognizeNv21(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): Task<List<FoundBlock>> =
        process(InputImage.fromByteArray(nv21, width, height, rotationDegrees, InputImage.IMAGE_FORMAT_NV21))

    private fun process(image: InputImage): Task<List<FoundBlock>> =
        recognizer.process(image).onSuccessTask(DirectExecutor) { Tasks.forResult(blocksOf(it)) }

    override fun close() = recognizer.close()

    private fun blocksOf(text: Text): List<FoundBlock> = text.textBlocks.map { block ->
        FoundBlock(block.text, block.boundingBox?.let { FrameRect(it.left, it.top, it.right, it.bottom) }, null)
    }
}

/** A still decoded for ML Kit, with the rotation its EXIF orientation asks for. */
internal class DecodedStill(val bitmap: Bitmap, val rotationDegrees: Int)

/**
 * The Android recognizers: ML Kit, with one level for both [OcrLevel]s. Live frames reach it as NV21
 * built from the frame's luma with neutral chroma, so either analysis format reads the same way;
 * text needs no colour. A still is decoded from its file with its EXIF rotation.
 */
internal class AndroidTextRecognizers(
    private val newClient: () -> MlKitTextClient = ::LatinMlKitTextClient,
    private val decode: (String) -> DecodedStill? = ::decodeStill,
    private val liveTimeoutMillis: Long = LIVE_TIMEOUT_MILLIS,
) : TextRecognizerFactory {
    override fun live(level: OcrLevel): LiveTextRecognizer = MlKitLiveRecognizer(newClient(), liveTimeoutMillis)

    override fun still(level: OcrLevel): StillTextRecognizer = MlKitStillRecognizer(newClient, decode)
}

private class MlKitLiveRecognizer(private val client: MlKitTextClient, private val timeoutMillis: Long) :
    LiveTextRecognizer {
    private var nv21 = ByteArray(0)
    private var nv21Width = -1
    private var nv21Height = -1

    override fun recognize(image: LumaImage): List<FoundBlock> {
        val lumaBytes = image.width * image.height
        if (image.width != nv21Width || image.height != nv21Height) {
            val chromaBytes = 2 * ((image.width + 1) / 2) * ((image.height + 1) / 2)
            nv21 = ByteArray(lumaBytes + chromaBytes)
            nv21.fill(NEUTRAL_CHROMA, lumaBytes)
            nv21Width = image.width
            nv21Height = image.height
        }
        image.bytes.copyInto(nv21, 0, 0, lumaBytes)
        return client.recognizeNv21(nv21, image.width, image.height, image.rotationDegrees).awaitBlocking(timeoutMillis)
    }

    override fun close() = client.close()
}

private class MlKitStillRecognizer(
    private val newClient: () -> MlKitTextClient,
    private val decode: (String) -> DecodedStill?,
) : StillTextRecognizer {
    private var client: MlKitTextClient? = null

    override suspend fun recognize(path: String): List<FoundBlock> {
        val still = decode(path) ?: throw IOException("The image could not be decoded")
        require(still.bitmap.width > 0 && still.bitmap.height > 0) { "Invalid bitmap dimensions" }
        val created = newClient().also { client = it }
        return created.recognize(still.bitmap, still.rotationDegrees).await()
    }

    override fun close() {
        client?.close()
        client = null
    }
}

internal fun decodeStill(path: String): DecodedStill? {
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    val rotation =
        try {
            when (
                ExifInterface(
                    path,
                ).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: IOException) {
            CameraKLogger.e(TAG, "reading a still's EXIF orientation failed; it is read unrotated", e)
            0
        }
    return DecodedStill(bitmap, rotation)
}

// The listeners only resume or count down, so they run on whichever thread completes the task
// instead of being posted to the main thread.
private val DirectExecutor = Executor(Runnable::run)

// Cancelling the caller resumes at once; the task's late result is then dropped.
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener(DirectExecutor) { continuation.resume(it) }
    addOnFailureListener(DirectExecutor) { continuation.resumeWithException(it) }
}

// Live frames run on the frame registration's worker, never the main thread, so they wait for ML
// Kit there. Tasks.await would refuse Robolectric's test thread, which is the main one.
private fun <T> Task<T>.awaitBlocking(timeoutMillis: Long): T {
    if (!isComplete) {
        val done = CountDownLatch(1)
        addOnCompleteListener(DirectExecutor) { done.countDown() }
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("ML Kit did not answer within $timeoutMillis ms")
        }
    }
    exception?.let { throw it }
    check(!isCanceled) { "ML Kit cancelled the recognition" }
    return result
}

private const val TAG = "TextRecognition"

private const val NEUTRAL_CHROMA: Byte = 128.toByte()

// ML Kit answers a frame in tens of milliseconds; a frame it has not answered in this long is
// abandoned, so the registration's worker moves on.
private const val LIVE_TIMEOUT_MILLIS = 10_000L
