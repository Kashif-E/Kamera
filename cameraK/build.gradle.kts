plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val desktopMain by getting {
            dependencies {
                // JavaCV's own POM pulls the bindings for a dozen native libraries; core uses only
                // FFmpeg and videoInput. No natives: the app adds those for the OSes it ships.
                api(libs.javacv.get().let { "${it.module}:${it.version}" }) { exclude(group = "org.bytedeco") }
                api(libs.javacpp)
                implementation(libs.ffmpeg)
                implementation(libs.videoinput)
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kermit)
            api(libs.compose.ui)
            api(libs.compose.material3)
            api(libs.compose.foundation)
            implementation(libs.atomicfu)
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
            api(libs.camera.video)
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.startup.runtime)
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

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
            withSourcesJar()
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
val repositoryUrl = providers.gradleProperty("POM_URL").get()

tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    moduleName.set("CameraK")
    moduleVersion.set(project.version.toString())
    outputDirectory.set(file("${layout.buildDirectory}/dokka/html"))

    pluginsMapConfiguration.set(
        mapOf(
            "org.jetbrains.dokka.base.DokkaBase" to """{
            "customStyleSheets": ["styles/custom.css"],
            "customAssets": [],
            "homepageLink": "$repositoryUrl",
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
                remoteUrl.set(uri("$repositoryUrl/tree/main/cameraK/src").toURL())
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
