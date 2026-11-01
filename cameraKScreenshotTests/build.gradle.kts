// Paparazzi (layoutlib) snapshot tests for cameraK's composables. Not published.
//
// They live outside cameraK for two reasons. Paparazzi 2.0.0-alpha02, the newest release that runs
// on JDK 17, mis-scopes its dependencies in Kotlin Multiplatform modules (fixed in alpha05, which
// needs Java 21), so it gets a plain Android library module. And layoutlib's ~170 MB of jars and
// native runtime stay off cameraK's own unit-test classpath.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.paparazzi)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.kashif.cameraK.screenshots"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    testImplementation(projects.cameraK)
    testImplementation(libs.junit)
}

// Paparazzi 2.0.0-alpha02 replaces the HTML test report with its own reporter, which calls a
// Gradle-internal method removed in Gradle 9 and crashes the task after the tests have run.
// Failure diffs still go to build/paparazzi/failures.
tasks.withType<Test>().configureEach {
    reports.html.required.set(false)
}

// The Android unit-test compile sees android.jar instead of the JDK, which has no java.awt or
// javax.imageio. PixelExactSnapshotVerifier needs both and runs on the host JVM, like Paparazzi.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.noJdk.set(false)
}
