package org.company.app

import org.kmp.playground.kflite.DelegateType
import org.kmp.playground.kflite.InterpreterOptions
import org.kmp.playground.kflite.Kflite
import org.kmp.playground.kflite.bytesToScaledByteBuffer

@Composable
fun ByteArray.runTFliteModel() {
    val scope = rememberCoroutineScope()
    scope.launch {
        Kflite.init(
            model = Res.readBytes("files/efficientdet-lite2.tflite"),
            options = InterpreterOptions(
                numThreads = 4,
                delegateType = DelegateType.NNAPI_COREML,
                allowFp16PrecisionForFp32 = true
            )
        )

        println("TensorInputCount: ${Kflite.getInputTensorCount()}")
        println("TensorOutputCount: ${Kflite.getOutputTensorCount()}")

        // Prepare input data: Example model takes 4D array as an input
        val inputImageWidth = Kflite.getInputTensor(0).shape[1]
        val inputImageHeight = Kflite.getInputTensor(0).shape[2]
        val modelInputSize =
            FLOAT_TYPE_SIZE * inputImageWidth * inputImageHeight * PIXEL_SIZE

        // Prepare output data: Example model has 3D array as an output
        val firstOutputShape = Kflite.getOutputTensor(0).shape[0]
        val secondOutputShape = Kflite.getOutputTensor(0).shape[1]
        val thirdOutputShape = Kflite.getOutputTensor(0).shape[2]

        println("firstOutputTensor: ${firstOutputShape}, secondOutputTensorShape: $secondOutputShape, ThirdOutputTensorShape: $thirdOutputShape")

        val modelOutputSize = Array(firstOutputShape) {
            Array(secondOutputShape) {
                FloatArray(thirdOutputShape)
            }
        }

        // Run model
        Kflite.run(
            listOf(
                this.bytesToScaledByteBuffer(
                    inputWidth = inputImageWidth,
                    inputHeight = inputImageHeight,
                    inputAllocateSize = modelInputSize
                )
            ),
            mapOf(Pair(0, modelOutputSize))
        )
        println("Output of first detection: ${modelOutputSize[0][0].joinToString()}")

        // Close model after use
        Kflite.close()
    }
}
