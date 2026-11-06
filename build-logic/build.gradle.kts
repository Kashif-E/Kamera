plugins {
    `kotlin-dsl`
}

// A precompiled plugin can apply another plugin only by id, so the plugins the conventions apply come
// onto this build's classpath here, each through the marker artifact of its catalog [plugins] entry.
fun plugin(plugin: Provider<PluginDependency>) =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(plugin(libs.plugins.multiplatform))
    implementation(plugin(libs.plugins.android.library))
    implementation(plugin(libs.plugins.dokka))
    implementation(plugin(libs.plugins.vanniktech.publish))
}
