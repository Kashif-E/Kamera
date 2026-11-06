// camerak-testing: fakes and coroutine test helpers for apps' tests. Published, and meant for test
// source sets only: FakeCameraEngine, FakeFrameSource, FakePermissionChecker and the TestScope helpers.
plugins {
    id("camerak.kmp-library")
    id("camerak.publish")
}

kotlin {
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
}

// The @sample functions the KDoc names live in the desktop tests (com.kashif.cameraK.samples). Only
// commonMain gets them: Dokka refuses two source sets that share a sample root.
dokka {
    dokkaSourceSets.named("commonMain") {
        samples.from("src/desktopTest/kotlin")
    }
}
