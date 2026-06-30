import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.compose.compose

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("org.jetbrains.compose")
    alias(libs.plugins.compose.compiler)
    id("com.vanniktech.maven.publish") version "0.31.0"
    alias(libs.plugins.dokka)
}

group = "com.kashif.ocr_plugin"
version = "1.1"

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release", "debug")
    }

    jvm("desktop")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "ocrPlugin"
            isStatic = true
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(libs.javacv.platform)
                implementation(libs.tesseract.platform)
            }
        }

        commonMain.dependencies {
            api(projects.cameraK)
            implementation(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.text.recognition)
        }
    }

    // https://kotlinlang.org/docs/native_objc_interop.html#export_of_kdoc_comments_to_generated_objective_c_headers
//    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
//        compilations["main"].compilerOptions.options.freeCompilerArgs.add("_Xexport_kdoc")
//    }
}

android {
    namespace = "com.kashif.ocr_plugin"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
            withSourcesJar()
        }

        // For debug variant, we exclude Javadoc and sources to prevent conflicts
        singleVariant("debug") {
            // Exclude Javadoc and sources JARs for debug variant
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.kashif-mehmood-km",
        artifactId = "ocr_plugin",
        version = "1.1",
    )

    pom {
        name.set("qrScannerPlugin")
        description.set("Image Saver Plugin for CameraK")
        inceptionYear.set("2024")
        url.set("https://github.com/kashif-e/CameraK")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("Kashif-E")
                name.set("Kashif")
                email.set("kashismails@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/kashif-e/CameraK")
        }
    }

    // Configure publishing to Maven Central
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Enable GPG signing for all publications
    signAllPublications()
}
