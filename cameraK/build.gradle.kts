import org.jetbrains.compose.compose

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("org.jetbrains.compose")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release", "debug")
    }
    jvm("desktop")

    listOf(
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
                // JavaCV's own POM pulls the bindings for a dozen native libraries; core uses only
                // FFmpeg and videoInput. No natives: the app adds those for the OSes it ships.
                api(libs.javacv.get().let { "${it.module}:${it.version}" }) { exclude(group = "org.bytedeco") }
                api(libs.javacpp)
                api(libs.ffmpeg)
                implementation(libs.videoinput)
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kermit)
            api(compose.ui)
            api(compose.material3)
            api(compose.foundation)
            api(libs.coil3.compose)
            api(libs.coil3.ktor)
            api(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))

            // Test-only, and it must stay that way. On the runtime classpath, coroutines-test
            // installs TestMainDispatcherFactory via ServiceLoader, which takes over
            // Dispatchers.Main and then throws "Dispatchers.Main was accessed when the platform
            // dispatcher was absent and the test dispatcher was unset" in consumer apps that never
            // call Dispatchers.setMain(). See issue #165.
            implementation(libs.kotlinx.coroutines.test)
        }

        val desktopTest by getting {
            dependencies {
                // Skiko natives for the host, needed to render composables in screenshot tests.
                implementation(compose.desktop.currentOs)
            }
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
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
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    ndkVersion = "26.3.11579264" // NDK r26b+ uses 16KB page size defaults for native binaries

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

// The desktop tests allocate real JavaCV frames and recorders, so they need this machine's natives.
dependencies {
    val javacppPlatform = rootProject.extra["javacppPlatform"] as String
    val desktopTestRuntimeOnly = kotlin.sourceSets.getByName("desktopTest").runtimeOnlyConfigurationName
    listOf(libs.javacpp, libs.ffmpeg).forEach {
        add(desktopTestRuntimeOnly, variantOf(it) { classifier(javacppPlatform) })
    }
}

// Desktop screenshot tests compare against PNGs in src/desktopTest/resources/goldens.
// -Pcamerak.recordScreenshots=true rewrites the goldens instead (scripts/verify.sh --record-screenshots).
tasks.named<Test>("desktopTest") {
    val record = providers.gradleProperty("camerak.recordScreenshots").orElse("false")
    inputs.property("camerak.recordScreenshots", record)
    systemProperty("camerak.screenshots.record", record.get())
    systemProperty(
        "camerak.screenshots.goldenDir",
        layout.projectDirectory
            .dir("src/desktopTest/resources/goldens")
            .asFile.absolutePath,
    )
    systemProperty(
        "camerak.screenshots.diffDir",
        layout.buildDirectory
            .dir("reports/screenshots-desktop")
            .get()
            .asFile.absolutePath,
    )
}

// Google Play SDK Console ownership token: androidMain/resources puts it in the AAR's classes.jar;
// this also puts it in the root (metadata) artifact, which is what the Maven coordinate resolves to.
tasks.named<Jar>("allMetadataJar") {
    from("src/androidMain/resources")
}
tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    moduleName.set("CameraK")
    moduleVersion.set(project.version.toString())
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
