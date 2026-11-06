
plugins {
    id("camerak.kmp-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("camerak.publish")
}

kotlin {
    sourceSets {
        val desktopMain by getting
        commonMain.dependencies {
            api(projects.cameraK)
        }

        appleMain.dependencies {
            implementation(libs.kotlinx.io.core)
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

        val desktopTest by getting {
            dependencies {
                // Skiko natives for the host: SaverDeprecationHintTest composes the 1.x remember hint.
                implementation(compose.desktop.currentOs)
            }
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }
    }
}

android {
    namespace = "com.kashif.mediasaverplugin"
}
