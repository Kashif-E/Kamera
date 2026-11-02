// Bill of materials: one version for the core and every plugin. The root build adds a constraint
// for each published module, so the list lives in one place (publishedModules in the root build).
plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.publish)
}
