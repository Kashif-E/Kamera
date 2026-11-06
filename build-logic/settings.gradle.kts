// The convention plugins of every published module: camerak.kmp-library, camerak.publish and
// camerak.relocation. The root settings include this build in pluginManagement, so a module applies
// them by id. Their plugin versions come from the root catalog, the one place versions live.
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
        // In the order the root settings resolve plugins, so each plugin resolves from where it does there.
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
