plugins {
    id("camerak.kmp-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("camerak.publish")
}

kotlin {
    // Android and desktop share one ZXing decoder: jvmMain sits between commonMain and the two JVM targets.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvm") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.zxing.core)
            }
        }

        // javase renders the test images. It stays off desktopMain, where checkPublishedClasspath forbids it.
        val desktopTest by getting {
            dependencies {
                implementation(libs.zxing.javase)
            }
        }

        commonMain.dependencies {
            api(projects.cameraK)
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
        }
    }
}

android {
    namespace = "com.kashif.qrscannerplugin"
}

// The @sample functions the KDoc names. Only commonMain gets them: Dokka refuses two source sets that
// share a sample root, and every Android source set would.
dokka {
    dokkaSourceSets.named("commonMain") {
        samples.from("src/commonTest/kotlin")
    }
}
