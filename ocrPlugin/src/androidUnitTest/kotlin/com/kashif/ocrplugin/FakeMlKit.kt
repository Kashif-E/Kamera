package com.kashif.ocrplugin

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** An NV21 image a [FakeMlKitClient] was handed, copied. */
internal class SeenNv21(val bytes: List<Byte>, val width: Int, val height: Int, val rotationDegrees: Int)

/** Stands in for ML Kit: answers every recognition with [answer] and counts its closes. */
internal class FakeMlKitClient(private val answer: () -> Task<List<FoundBlock>>) : MlKitTextClient {
    val closes = AtomicInteger()
    val nv21: MutableList<SeenNv21> = CopyOnWriteArrayList()
    val bitmaps: MutableList<Pair<Bitmap, Int>> = CopyOnWriteArrayList()

    override fun recognize(bitmap: Bitmap, rotationDegrees: Int): Task<List<FoundBlock>> {
        bitmaps += bitmap to rotationDegrees
        return answer()
    }

    override fun recognizeNv21(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): Task<List<FoundBlock>> {
        this.nv21 += SeenNv21(nv21.toList(), width, height, rotationDegrees)
        return answer()
    }

    override fun close() {
        closes.incrementAndGet()
    }
}

internal fun textTask(text: String): Task<List<FoundBlock>> = Tasks.forResult(listOf(FoundBlock(text, null, null)))

internal fun stillBitmap(): DecodedStill = DecodedStill(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0)
