package com.kashif.ocrPlugin

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
import java.io.IOException
import java.util.concurrent.Executor

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

/** [MlKitTextClient] over ML Kit's bundled Latin text recognizer. */
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
    override fun live(level: OcrLevel): LiveTextRecognizer =
        TODO("OC': ML Kit live recognizer over $newClient, $liveTimeoutMillis")

    override fun still(level: OcrLevel): StillTextRecognizer = TODO("OC': ML Kit still recognizer over $decode")
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
            CameraKLogger.e(TAG, "OcrPlugin: reading a still's EXIF orientation failed; it is read unrotated", e)
            0
        }
    return DecodedStill(bitmap, rotation)
}

// The listeners only resume, so they run on whichever thread completes the task instead of being
// posted to the main thread.
private val DirectExecutor = Executor(Runnable::run)

private const val TAG = "CameraK"

// ML Kit answers a frame in tens of milliseconds; a frame it has not answered in this long is
// abandoned, so the registration's worker moves on.
private const val LIVE_TIMEOUT_MILLIS = 10_000L
