// Publishing for every CameraK artifact. vanniktech maven-publish reads the coordinates and the POM from
// gradle.properties (GROUP, VERSION_NAME and the shared POM_* keys in the root file, POM_ARTIFACT_ID,
// POM_NAME and POM_DESCRIPTION in the module's), and mavenCentralPublishing and signAllPublications from
// the same place, so -PsignAllPublications=false publishes unsigned (scripts/verify.sh --full).
plugins {
    id("com.vanniktech.maven.publish")
}
