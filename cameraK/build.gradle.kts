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

group = "com.kashif.camera_compose"
version = "0.4"

kotlin {
    jvmToolchain(11)
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
            baseName = "cameraK"
            isStatic = true
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                api(libs.javacv.platform)
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.coroutines.test)
            api(libs.kermit)
            api(compose.ui)
            api(compose.material3)
            api(compose.foundation)
            api(libs.coil3.compose)
            api(libs.coil3.ktor)
            api(libs.atomicfu)
        }

        commonTest.dependencies {
            api(kotlin("test"))
        }

        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
            api(libs.camera.core)
            api(libs.camera.camera2)
            api(libs.androidx.camera.view)
            api(libs.camera.lifecycle)
            api(libs.camera.extensions)
            api(libs.camera.video)
            api(libs.androidx.activityCompose)
            api(libs.androidx.startup.runtime)
            api(libs.core)
        }
    }

    // https://kotlinlang.org/docs/native_objc_interop.html#export_of_kdoc_comments_to_generated_objective_c_headers
//    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
//        compilations["main"].compilerOptions.options.freeCompilerArgs.add("_Xexport_kdoc")
//    }
}

android {
    namespace = "com.kashif.cameraK"
    compileSdk = 36
    ndkVersion = "26.3.11579264" // NDK r26b+ uses 16KB page size defaults for native binaries

    defaultConfig {
        minSdk = 21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false // required for 16KB page-size compliant native libs in AAB/APK
        }
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
        artifactId = "camerak",
        version = "0.4",
    )

    pom {
        name.set("CameraK")
        description.set("Camera Library to work on both Android/iOS.")
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

tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    moduleName.set("CameraK")
    moduleVersion.set("0.4")
    outputDirectory.set(file("${layout.buildDirectory}/dokka/html"))

    pluginsMapConfiguration.set(
        mapOf(
            "org.jetbrains.dokka.base.DokkaBase" to """{
            "customStyleSheets": ["styles/custom.css"],
            "customAssets": [],
            "homepageLink": "https://github.com/kashif-e/CameraK",
            "footerMessage": "© 2024 CameraK - Multiplatform Camera SDK",
            "separateInheritedMembers": false,
            "mergeImplicitExpectActualDeclarations": true,
            "sourceSetDependencies": {},
            "hideSourceSets": false
        }""",
        ),
    )

    dokkaSourceSets {
        configureEach {
            includes.from("README.md")
            skipDeprecated.set(false)
            reportUndocumented.set(true)
            noStdlibLink.set(false)
            noJdkLink.set(false)

            sourceLink {
                localDirectory.set(file("src"))
                remoteUrl.set(uri("https://github.com/kashif-e/CameraK/tree/main/cameraK/src").toURL())
                remoteLineSuffix.set("#L")
            }
        }
    }
}

// Create task to copy and inject custom CSS after Dokka generation
tasks.register("injectCustomCSS") {
    dependsOn("dokkaGeneratePublicationHtml")
    doLast {
        val customCssSource = file("src/dokka/assets/styles/custom.css")
        val customCssDest = file("${layout.buildDirectory}/dokka/html/styles/custom.css")

        // Copy custom CSS
        if (customCssSource.exists()) {
            customCssDest.parentFile.mkdirs()
            customCssSource.copyTo(customCssDest, overwrite = true)
            println("✓ Custom CSS copied to build output")
        }

        // Inject custom CSS link into HTML files
        fileTree("${layout.buildDirectory}/dokka/html") {
            include("**/*.html")
        }.forEach { htmlFile ->
            val content = htmlFile.readText()
            if (!content.contains("custom.css")) {
                val updated =
                    content.replace(
                        "styles/font-jb-sans-auto.css\" rel=\"Stylesheet\">",
                        "styles/font-jb-sans-auto.css\" rel=\"Stylesheet\">\n        <link href=\"styles/custom.css\" rel=\"Stylesheet\">",
                    )
                htmlFile.writeText(updated)
            }
        }
        println("✓ Custom CSS injected into HTML files")
    }
}
