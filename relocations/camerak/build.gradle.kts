// The 1.x artifact camerak, published in 2.0 under the new group with the same id: a POM-only publication
// under LEGACY_GROUP whose <relocation> the root build adds (relocatedArtifacts), so a build that still
// names the 1.x coordinate resolves the 2.0 one.
plugins {
    id("camerak.relocation")
}
