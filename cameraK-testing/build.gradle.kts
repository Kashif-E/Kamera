// camerak-testing: fakes and coroutine test helpers for apps' tests. Published, and meant for test
// source sets only: FakeCameraEngine, FakeFrameSource, FakePermissionChecker and the TestScope helpers.
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
        commonMain.dependencies {
            api(project(":cameraK-core"))
            // The one test library a published module may export, and only this one: apps use this
            // module from test source sets, where coroutines-test belongs (checkTestOnlyDependencies,
            // testingModuleMayExportCoroutinesTest).
            api(libs.kotlinx.coroutines.test)
            // FakePermissionChecker's statuses. Never api: no public signature names an atomicfu type.
            implementation(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }
    }
}

android {
    namespace = "com.kashif.cameraK.testing"
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
