package org.company.app

// ponytail: kflite (TensorFlow Lite) ships no desktop/JVM artifact, so inference
// is unavailable on desktop. No-op keeps the shared sample compiling for the jvm target.
actual suspend fun ByteArray.runTFliteModel() {
    println("runTFliteModel: TFLite inference is not supported on desktop — skipping.")
}
