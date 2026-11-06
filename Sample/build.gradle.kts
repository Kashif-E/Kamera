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
        val desktopMain by getting {
            // Packages Tesseract's eng.traineddata so desktop OCR finds it (see OcrConfig).
            resources.srcDir(rootProject.file("ocrPlugin/model"))
        }

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
                // BareCameraXActivity, the release gate's bare-CameraX baseline, and its trace section.
                implementation(libs.camera.camera2)
                implementation(libs.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.androidx.tracing)
            }
        }

        iosMain {
            dependsOn(mobileMain)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // The fake holder, for the tests of the Sample's wiring to the 2.0 API.
            implementation(projects.cameraKTesting)
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

    buildTypes {
        // What :benchmark measures. Macrobenchmark refuses a debuggable app, so this is release,
        // signed with the debug key so it installs, and profileable from the shell (the manifest).
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
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

            // Without a camera usage description TCC denies or kills a packaged macOS app that opens
            // the camera; the entitlements let a signed, hardened-runtime build use it too.
            macOS {
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>NSCameraUsageDescription</key>
                        <string>The CameraK sample shows the camera preview and takes photos and videos.</string>
                        """.trimIndent()
                }
                entitlementsFile.set(project.file("macos/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("macos/runtime-entitlements.plist"))
            }
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

// A packaged macOS app that opens the camera without NSCameraUsageDescription in its Info.plist is
// denied or killed by TCC, and a hardened-runtime build also needs the camera entitlement.
// `./gradlew run` hides both, because the terminal already holds the camera permission. The lint
// target of scripts/verify.sh runs this check.
// The DSL is read when the check runs, after the whole script has configured it, so the check does
// not depend on where it is declared.
val macOsPackaging = compose.desktop.application.nativeDistributions.macOS
val macOsExtraPlistKeys =
    provider {
        var keys = ""
        macOsPackaging.infoPlist { keys = extraKeysRawXml.orEmpty() }
        keys
    }
val macOsEntitlementFiles =
    provider {
        listOfNotNull(
            macOsPackaging.entitlementsFile.orNull?.asFile,
            macOsPackaging.runtimeEntitlementsFile.orNull?.asFile,
        )
    }
tasks.register("checkMacOsCameraPackaging") {
    group = rootProject.extra["lintGroup"] as String
    description = "Fails if the packaged macOS app lacks a camera usage description or the camera entitlement"
    inputs.property("extraKeysRawXml", macOsExtraPlistKeys)
    inputs.files(macOsEntitlementFiles)
    doLast {
        val plistKeys = macOsExtraPlistKeys.get()
        val entitlements = macOsEntitlementFiles.get()
        val problems = mutableListOf<String>()
        val usage = Regex("""<key>NSCameraUsageDescription</key>\s*<string>\s*[^<\s][^<]*</string>""")
        if (!usage.containsMatchIn(plistKeys)) {
            problems += "macOS { infoPlist { extraKeysRawXml } } has no non-empty NSCameraUsageDescription"
        }
        val cameraEntitlement = Regex("""<key>com\.apple\.security\.device\.camera</key>\s*<true\s*/>""")
        if (entitlements.size < 2) {
            problems += "macOS { entitlementsFile / runtimeEntitlementsFile } are not both set"
        }
        entitlements.filterNot { it.isFile && cameraEntitlement.containsMatchIn(it.readText()) }.forEach {
            problems += "${it.name} does not grant com.apple.security.device.camera"
        }
        if (problems.isNotEmpty()) {
            throw GradleException("The macOS package cannot use the camera:\n" + problems.joinToString("\n"))
        }
    }
}
