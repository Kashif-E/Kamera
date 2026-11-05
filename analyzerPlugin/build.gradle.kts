plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.poko)
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
    }
}

android {
    namespace = "com.kashif.analyzer_plugin"
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
