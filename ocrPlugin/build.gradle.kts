plugins {
    id("camerak.kmp-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("camerak.publish")
    alias(libs.plugins.poko)
}

kotlin {
    sourceSets {
        val desktopMain by getting {
            dependencies {
                // Java bindings only; the app adds the Tesseract and Leptonica natives it ships.
                implementation(libs.tesseract)
                implementation(libs.leptonica)
            }
        }

        commonMain.dependencies {
            api(projects.cameraK)
            implementation(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(projects.cameraKTesting)

            // Test-only, and it must stay that way. On the runtime classpath, coroutines-test
            // installs TestMainDispatcherFactory via ServiceLoader, which takes over
            // Dispatchers.Main and then throws "Dispatchers.Main was accessed when the platform
            // dispatcher was absent and the test dispatcher was unset" in consumer apps that never
            // call Dispatchers.setMain(). See issue #165.
            implementation(libs.kotlinx.coroutines.test)
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }

        androidMain.dependencies {
            implementation(libs.text.recognition)
        }
    }
}

// One desktop test runs the real Tesseract, so the tests need this machine's natives.
dependencies {
    val javacppPlatform = rootProject.extra["javacppPlatform"] as String
    val desktopTestRuntimeOnly = kotlin.sourceSets.getByName("desktopTest").runtimeOnlyConfigurationName
    listOf(libs.javacpp, libs.tesseract, libs.leptonica).forEach {
        add(desktopTestRuntimeOnly, variantOf(it) { classifier(javacppPlatform) })
    }
}

// Desktop tests run in a working directory of their own, so a test can plant files there and show
// that OCR never reads them. The real-Tesseract test finds the model through ocr.tessdataDir.
tasks.named<Test>("desktopTest") {
    val testWorkingDir =
        layout.buildDirectory
            .dir("desktopTest-cwd")
            .get()
            .asFile
    workingDir = testWorkingDir
    doFirst { testWorkingDir.mkdirs() }
    systemProperty("ocr.tessdataDir", file("model/com/kashif/ocrplugin/tessdata").absolutePath)
}

android {
    namespace = "com.kashif.ocrplugin"
}

// The @sample functions the KDoc names. Only commonMain gets them: Dokka refuses two source sets that
// share a sample root, and every Android source set would.
dokka {
    dokkaSourceSets.named("commonMain") {
        samples.from("src/commonTest/kotlin")
    }
}
