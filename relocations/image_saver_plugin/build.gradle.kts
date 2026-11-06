// The 1.x artifact image_saver_plugin, renamed camerak-media-saver in 2.0: a POM-only publication whose <relocation> the
// root build adds (relocatedArtifacts), so a build that still names image_saver_plugin resolves camerak-media-saver.
plugins {
    id("camerak.relocation")
}
