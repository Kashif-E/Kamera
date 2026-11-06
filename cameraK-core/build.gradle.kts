// camerak-core: the camera without Compose. State holder, platform controllers, state and
// configuration types, and the plugin interface. Compose UI is in camerak (:cameraK).
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.poko)
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm("desktop")

    // The os_signpost shim behind the iOS engine's Tracer: the signpost calls are C macros, so Kotlin
    // reaches them through the static wrappers in the .def.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("signpost") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/signpost.def"))
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                // JavaCV's own POM pulls the bindings for a dozen native libraries; core uses only
                // FFmpeg and videoInput. No natives: the app adds those for the OSes it ships.
                api(libs.javacv.get().let { "${it.module}:${it.version}" }) { exclude(group = "org.bytedeco") }
                api(libs.javacpp)
                implementation(libs.ffmpeg)
                implementation(libs.videoinput)
                // macOS camera authorization over the Objective-C runtime (Decision 15). Compile-only:
                // without JNA at runtime the status reads as not determined, and the open prompts.
                compileOnly(libs.jna)
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
            // bindToLifecycle(lifecycle: Lifecycle) names Lifecycle in a public signature. No Compose.
            api(libs.jetbrains.lifecycle.runtime)
        }

        commonTest {
            // The engine contract kit: never published, compiled into this module's tests on every
            // target. Each engine's test source set runs it through its own driver (design §11.2).
            kotlin.srcDir("src/engineContract/kotlin")
            dependencies {
                implementation(kotlin("test"))

                // Test-only, and it must stay that way. On the runtime classpath, coroutines-test
                // installs TestMainDispatcherFactory via ServiceLoader, which takes over
                // Dispatchers.Main and then throws "Dispatchers.Main was accessed when the platform
                // dispatcher was absent and the test dispatcher was unset" in consumer apps that never
                // call Dispatchers.setMain(). See issue #165.
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":cameraK-testing"))
            }
        }

        // CameraKEventShapeTest reads constructor visibility and annotations through kotlin-reflect.
        // Test-only: never on a main or published classpath.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("reflect"))
            }
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            // The tests host CameraX in a ComponentActivity, which came through activity-compose
            // before the split; pinned to the version it resolved to then.
            implementation(libs.androidx.activity)
        }

        // The release gate's device run of the contract kit (design §12.4 B1): five cases over real
        // CameraX, run by the maintainer with connectedDebugAndroidTest. commonTest is not in this
        // set's tree, so the kit comes in by its directory, as it does for commonTest.
        val androidInstrumentedTest by getting {
            kotlin.srcDir("src/engineContract/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
            }
        }

        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
            // CameraFrame.imageProxy names camera-core's ImageProxy; the dump names no other CameraX
            // artifact, so the rest are implementation (camerax_api_scope_matches_abi_dump). No main
            // source uses camera-view.
            api(libs.camera.core)
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.video)
            // The Android engine is its own LifecycleOwner over a LifecycleRegistry. No public
            // signature names a registry, so it is not api.
            implementation(libs.androidx.lifecycle.runtime)
            // The engine's trace sections (start, first frame). No public signature names it.
            implementation(libs.androidx.tracing)
        }
    }
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

tasks.named<Test>("desktopTest") {
    // The docs tests (com.kashif.cameraK.docs) read these, so a docs-only change must rerun them.
    inputs
        .files(
            layout.projectDirectory.file("../README.MD"),
            layout.projectDirectory.dir("../docs"),
            layout.projectDirectory.file("../mkdocs.yml"),
        ).withPropertyName("docs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // DocsApiTest.kdocSamplesCompile reads every module's KDoc and samples.
    inputs
        .files(
            fileTree(rootDir) {
                include("*/src/*Main/**/*.kt", "*/src/*Test/**/*.kt")
            },
        ).withPropertyName("kdocSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
