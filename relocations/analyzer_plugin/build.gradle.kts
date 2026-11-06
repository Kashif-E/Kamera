// The 1.x artifact analyzer_plugin, renamed camerak-analyzer in 2.0: a POM-only publication whose <relocation> the
// root build adds (relocatedArtifacts), so a build that still names analyzer_plugin resolves camerak-analyzer.
plugins {
    id("camerak.relocation")
}
