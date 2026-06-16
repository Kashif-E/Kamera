package org.company.app

import cameracompose.sample.generated.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kmp.playground.kflite.DelegateType
import org.kmp.playground.kflite.InterpreterOptions
import org.kmp.playground.kflite.Kflite
import org.kmp.playground.kflite.bytesToScaledByteBuffer
import kotlin.concurrent.Volatile
import kotlin.math.floor

const val FLOAT_TYPE_SIZE = 3
const val PIXEL_SIZE = 1

private const val SCORE_THRESHOLD = 0.4f

// The model is initialized once and reused; inference runs off the main thread, and a new frame is
// dropped while one is already in flight (the analyzer produces frames far faster than inference).
@Volatile private var modelReady = false
@Volatile private var inferenceInFlight = false

actual fun getTFliteRunner(): (ByteArray.(CoroutineScope) -> Unit)? = ByteArray::runTFliteModel

fun ByteArray.runTFliteModel(scope: CoroutineScope) {
    if (inferenceInFlight) return
    inferenceInFlight = true
    val frame = this
    scope.launch(Dispatchers.Default) {
        try {
            if (!modelReady) {
                Kflite.init(
                    model = Res.readBytes("files/efficientdet-lite2.tflite"),
                    options = InterpreterOptions(
                        numThreads = 4,
                        delegateType = DelegateType.NNAPI_COREML,
                        allowFp16PrecisionForFp32 = true,
                    ),
                )
                modelReady = true
            }

            val inputWidth = Kflite.getInputTensor(0).shape[1]
            val inputHeight = Kflite.getInputTensor(0).shape[2]
            val inputSize = FLOAT_TYPE_SIZE * inputWidth * inputHeight * PIXEL_SIZE

            // EfficientDet-Lite emits 4 outputs (boxes [1,N,4], classes [1,N], scores [1,N],
            // count [1]). Allocate each container from its real shape so output ordering doesn't
            // matter, then classify them by rank below.
            val outputs = HashMap<Int, Any>()
            for (i in 0 until Kflite.getOutputTensorCount()) {
                val shape = Kflite.getOutputTensor(i).shape
                outputs[i] = when (shape.size) {
                    3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                    2 -> Array(shape[0]) { FloatArray(shape[1]) }
                    else -> FloatArray(if (shape.isEmpty()) 1 else shape[0])
                }
            }

            Kflite.run(
                listOf(frame.bytesToScaledByteBuffer(inputWidth, inputHeight, inputSize)),
                outputs,
            )

            printDetections(outputs)
        } catch (e: Exception) {
            // Frame couldn't be processed (e.g. undecodable bytes) — skip it rather than crash.
        } finally {
            inferenceInFlight = false
        }
    }
}

/**
 * Decodes the model outputs and prints detections above [SCORE_THRESHOLD].
 *
 * The two rank-2 outputs are class ids vs. scores. Class ids are whole numbers; scores are
 * fractional 0..1, so the tensor whose values are all whole numbers is the class id tensor.
 */
private fun printDetections(outputs: Map<Int, Any>) {
    val rank2 = outputs.values.filterIsInstance<Array<FloatArray>>().map { it[0] }
    if (rank2.isEmpty()) return

    val classes = rank2.firstOrNull { row -> row.all { it == floor(it) } }
    val scores = rank2.firstOrNull { it !== classes } ?: return

    val detected = scores.indices
        .filter { scores[it] >= SCORE_THRESHOLD }
        .joinToString(", ") { i ->
            val id = classes?.getOrNull(i)?.toInt() ?: -1
            val label = COCO_LABELS.getOrNull(id) ?: "id=$id"
            "$label ${(scores[i] * 100).toInt()}%"
        }

    if (detected.isNotEmpty()) {
        println("TFLite detections: $detected")
    }
}

// Canonical 90-class COCO label map used by EfficientDet-Lite (with gaps for unused ids).
private val COCO_LABELS = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "???", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "???", "backpack",
    "umbrella", "???", "???", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
    "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "???", "wine glass", "cup", "fork", "knife", "spoon", "bowl",
    "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
    "cake", "chair", "couch", "potted plant", "bed", "???", "dining table", "???", "???", "toilet",
    "???", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
    "toaster", "sink", "refrigerator", "???", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
)
