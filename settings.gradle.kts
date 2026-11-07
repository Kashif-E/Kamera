
rootProject.name = "CameraCompose"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    // The convention plugins every published module applies (camerak.kmp-library, camerak.publish,
    // camerak.relocation).
    includeBuild("build-logic")
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
// camerak-ocr-tessdata-eng, the desktop OCR model as a resource jar (the root build's resourceModules).
include(":ocrTessdata")
include(":cameraKScreenshotTests")
include(":camerak-bom")
// The release gate's Macrobenchmark over the Sample app; built by verify.sh, run on a device by the maintainer.
include(":benchmark")

// POM-only relocations of the 1.x coordinates 2.0 moved to a new group or id (the root build's relocatedArtifacts).
include(":relocations:qr_scanner_plugin")
include(":relocations:ocr_plugin")
include(":relocations:analyzer_plugin")
include(":relocations:image_saver_plugin")
include(":relocations:camerak")
