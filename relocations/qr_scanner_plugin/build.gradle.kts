// The 1.x artifact qr_scanner_plugin, renamed camerak-qr-scanner in 2.0: a POM-only publication whose <relocation> the
// root build adds (relocatedArtifacts), so a build that still names qr_scanner_plugin resolves camerak-qr-scanner.
plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.publish)
}
