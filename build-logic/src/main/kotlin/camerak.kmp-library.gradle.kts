// A published CameraK library: the four targets every artifact ships, Java 17, explicit API mode, the
// ABI check against the dumps under api/, the Android SDK levels from the catalog, and API docs that
// fail on Dokka's warnings. The module's own build file keeps its dependencies and its namespace.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.dokka")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun sdkLevel(key: String): Int =
    catalog
        .findVersion(key)
        .orElseThrow { GradleException("gradle/libs.versions.toml has no version $key") }
        .requiredVersion
        .toInt()

kotlin {
    jvmToolchain(17)
    // Every published module states the visibility of each declaration, and checkKotlinAbi compares its
    // public ABI with the dumps committed under api/: a JVM dump per JVM target and one klib dump for the
    // native targets. After a reviewed change, ./gradlew updateKotlinAbi rewrites them (CONTRIBUTING.md).
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    androidTarget {
        publishLibraryVariants("release")
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
}

// The ABI check runs in verify.sh's lint target.
tasks.matching { it.name == "checkKotlinAbi" }.configureEach {
    group = rootProject.extra["lintGroup"] as String
}

android {
    compileSdk = sdkLevel("android-compileSdk")

    defaultConfig {
        minSdk = sdkLevel("android-minSdk")
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

// The API docs (the javadoc jar verify.sh --full publishes, and the site) fail on Dokka's warnings, so an
// unresolved @sample or link stops the build instead of scrolling past (CC-33).
dokka {
    dokkaPublications.configureEach {
        failOnWarning.set(true)
    }
}
