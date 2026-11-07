// A 1.x coordinate that has a 2.0 successor: a POM-only publication under LEGACY_GROUP, the 1.x group,
// whose <relocation> the root build adds (relocatedArtifacts). No Gradle module metadata is published
// beside the POM, because Gradle would read that instead and never see the relocation.
plugins {
    `java-platform`
    id("camerak.publish")
}

// vanniktech takes the group from GROUP, the 2.0 group; a relocation stays where 1.x published it.
mavenPublishing {
    coordinates(groupId = providers.gradleProperty("LEGACY_GROUP").get())
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}
