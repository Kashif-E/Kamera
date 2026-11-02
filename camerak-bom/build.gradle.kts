// Bill of materials: one version for the core and every plugin. The root build adds a constraint
// for each published library, so the list lives in one place (libraryModules).
plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.publish)
}
