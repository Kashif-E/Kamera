import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// camerak-ocr-tessdata-eng: Tesseract's English model as a resource jar, at the path desktop OCR reads
// from the classpath (ocrPlugin's TessdataLocator). camerak-ocr does not depend on it; an app adds it to
// ship the model. It has no Kotlin, so no targets, ABI or Android variant, and it is not a
// camerak.kmp-library module.
plugins {
    `java-library`
    id("camerak.publish")
}

// The module metadata's JVM variant declares this version, so it does not follow the JDK running Gradle.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

// Central requires a javadoc and a sources jar beside the jar. Both are empty: there is no code, and a
// sources jar of the main source set would hold the 23 MB model a second time.
mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Empty()))
}
