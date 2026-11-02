import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        // https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-test.html
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }

    jvm("desktop")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val desktopMain by getting

        val mobileMain by creating

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.material)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(projects.cameraK)
            implementation(projects.analyzerPlugin)
            implementation(projects.imageSaverPlugin)
            implementation(projects.qrScannerPlugin)
            implementation(projects.ocrPlugin)
            implementation(projects.videoRecorderPlugin)
            implementation(libs.lucide.icons.cmp)
        }

        mobileMain.dependsOn(commonMain.get())

        mobileMain.dependencies {
            implementation(libs.kflite)
            implementation(libs.atomicfu)
        }

        androidMain {
            dependsOn(mobileMain)
            dependencies {
                implementation(compose.uiTooling)
                implementation(libs.androidx.activityCompose)
            }
        }

        iosMain {
            dependsOn(mobileMain)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }

        androidMain.dependencies {
            implementation(compose.uiTooling)
            implementation(libs.androidx.activityCompose)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "org.company.app"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        // kflite, a Sample-only dependency, needs 24; the libraries need android-minSdk.
        minSdk = 24
        targetSdk = 34

        applicationId = "org.company.app.androidApp"
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

// The libraries ship only bytedeco's Java bindings, so the Sample adds the natives for the machine
// building it: JavaCPP's JNI glue, FFmpeg (camera and recording), Tesseract and Leptonica (OCR), and
// on Windows videoInput (camera capture).
dependencies {
    val javacppPlatform = rootProject.extra["javacppPlatform"] as String
    val desktopRuntimeOnly = kotlin.sourceSets.getByName("desktopMain").runtimeOnlyConfigurationName
    val natives =
        listOf(libs.javacpp, libs.ffmpeg, libs.tesseract, libs.leptonica) +
            if (javacppPlatform.startsWith("windows")) listOf(libs.videoinput) else emptyList()
    natives.forEach { add(desktopRuntimeOnly, variantOf(it) { classifier(javacppPlatform) }) }
}
tasks.named("embedAndSignAppleFrameworkForXcode") {
    doFirst {
        if (gradle.startParameter.isConfigurationCacheRequested) {
            throw GradleException("Configuration cache is not supported for this task")
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.company.app"
            packageVersion = "1.0.0"
        }

        afterEvaluate {
            tasks.withType<JavaExec> {
                jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
                jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED") // recommended but not necessary

                if (System.getProperty("os.name").contains("Mac")) {
                    jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
                    jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
                }
            }
        }
    }
}
