// The release gate's Macrobenchmark (design §12.4 B1). It drives the Sample's benchmark build on a
// device and reads CameraK's and CameraX's trace sections. Not published. verify.sh's sample target
// builds it; only the maintainer runs it, on a Pixel 7:
//   ./gradlew :benchmark:connectedBenchmarkAndroidTest
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.jetbrains.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.kashif.cameraK.benchmark"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        // androidx.benchmark's macro library needs 24, the Sample's own minimum.
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Matches the Sample's benchmark build type. The test APK itself may be debuggable.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":Sample"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// Only the benchmark variant: the debug one would measure a debuggable app, which Macrobenchmark refuses.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
