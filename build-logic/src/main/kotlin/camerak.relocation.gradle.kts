// A 1.x artifact id that 2.0 renamed: a POM-only publication whose <relocation> the root build adds
// (relocatedArtifacts). No Gradle module metadata is published beside the POM, because Gradle would read
// that instead and never see the relocation.
plugins {
    `java-platform`
    id("camerak.publish")
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}
