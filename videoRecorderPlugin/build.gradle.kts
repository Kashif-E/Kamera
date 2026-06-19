import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("org.jetbrains.compose")
    alias(libs.plugins.compose.compiler)
    id("com.vanniktech.maven.publish") version "0.31.0"
    alias(libs.plugins.dokka)
}

group = "com.kashif.video_recorder_plugin"
version = "1.0"

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release", "debug")
    }
    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "videorecorderplugin"
            isStatic = true
        }
    }

    sourceSets {
        val desktopMain by getting
        commonMain.dependencies {
            api(projects.cameraK)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
        }
    }
}

android {
    namespace = "com.kashif.video_recorder_plugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
            withSourcesJar()
        }

        singleVariant("debug") {
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.kashif-mehmood-km",
        artifactId = "video_recorder_plugin",
        version = "1.0",
    )

    pom {
        name.set("VideoRecorderPlugin")
        description.set("Video Recorder Plugin for CameraK")
        inceptionYear.set("2024")
        url.set("https://github.com/kashif-e/CameraK")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("Kashif-E")
                name.set("Kashif")
                email.set("kashismails@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/kashif-e/CameraK")
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()
}
