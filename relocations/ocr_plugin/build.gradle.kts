// The 1.x artifact ocr_plugin, renamed camerak-ocr in 2.0: a POM-only publication whose <relocation> the
// root build adds (relocatedArtifacts), so a build that still names ocr_plugin resolves camerak-ocr.
plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.publish)
}
