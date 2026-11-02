// camerak-core: the camera without Compose. State holder, platform controllers, state and
// configuration types, and the plugin interface. Compose UI is in camerak (:cameraK).
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
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
            implementation(libs.atomicfu)
        }

        commonTest {
            // The engine contract kit: never published, compiled into this module's tests on every
            // target. It is empty until the engine SPI lands (design §0).
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

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            // The tests host CameraX in a ComponentActivity and drive a LifecycleRegistry. Both came
            // through activity-compose before the split; pinned to the versions they resolved to then.
            implementation(libs.androidx.activity)
            implementation(libs.androidx.lifecycle.runtime)
        }

        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
            api(libs.camera.core)
            api(libs.camera.camera2)
            api(libs.androidx.camera.view)
            api(libs.camera.lifecycle)
            api(libs.camera.video)
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
    // IosInstallDocsTest reads these, so a docs-only change must rerun it.
    inputs
        .files(
            layout.projectDirectory.file("../README.MD"),
            layout.projectDirectory.file("../docs/getting-started/installation.md"),
        ).withPropertyName("iosInstallDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
