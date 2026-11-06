
rootProject.name = "CameraCompose"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
    }
}
include(":cameraK-core")
include(":cameraK")
include(":cameraK-testing")
include(":Sample")
include(":ImageSaverPlugin")
include(":qrScannerPlugin")
include(":analyzerPlugin")

include("ocrPlugin")
include(":cameraKScreenshotTests")
include(":camerak-bom")

// POM-only relocations of the 1.x artifact ids 2.0 renamed (the root build's relocatedArtifacts).
include(":relocations:qr_scanner_plugin")
include(":relocations:ocr_plugin")
include(":relocations:analyzer_plugin")
include(":relocations:image_saver_plugin")
