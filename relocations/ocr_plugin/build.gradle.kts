// The 1.x artifact ocr_plugin, renamed camerak-ocr in 2.0: a POM-only publication whose <relocation> the
// root build adds (relocatedArtifacts), so a build that still names ocr_plugin resolves camerak-ocr.
plugins {
    id("camerak.relocation")
}
