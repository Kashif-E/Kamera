package com.kashif.ocrplugin

import com.kashif.cameraK.utils.CameraKLogger
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Desktop OCR loads Tesseract's language data from the integrator's explicit path, then a packaged
 * model verified against its pinned SHA-256 and extracted to a per-user directory, then
 * TESSDATA_PREFIX. It never reads this repository's source tree or the process working directory,
 * where anyone who can write a file would choose the model native Tesseract parses.
 *
 * The packaged model lives under the 2.0 package's lowercase directory. An app that packages it at
 * the 1.x directory, `com/kashif/ocrPlugin/tessdata`, keeps working and is told once to move it. A
 * map stands in for the classpath: a jar's entries are case-sensitive, while this machine's disk,
 * where a directory classpath would live, is not.
 */
class TessdataLocatorTest {
    private val root = Files.createTempDirectory("tessdata-locator").toFile()
    private val cacheDir = File(root, "cache")

    @AfterTest
    fun deleteFiles() {
        root.deleteRecursively()
    }

    @Test
    fun aModelPlantedInTheWorkingDirectory_isNeverLoaded() {
        val workingDir = File(System.getProperty("user.dir"))
        val plantedRoot = File(workingDir, "ocrPlugin")
        assertFalse(plantedRoot.exists(), "test working directory already has $plantedRoot")
        val planted = File(workingDir, "ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin")
        planted.mkdirs()
        File(planted, "eng.traineddata").writeBytes(PLANTED_MODEL)
        try {
            assertNull(findTessdataDirectory(sources()), "lookup with no configured source")

            val prefix = modelDir("prefix")
            assertSameDir(prefix, findTessdataDirectory(sources(environment = mapOf("TESSDATA_PREFIX" to prefix.path))))
        } finally {
            plantedRoot.deleteRecursively()
        }
    }

    @Test
    fun anExplicitPath_winsOverThePackagedModelAndTessdataPrefix() {
        val explicit = modelDir("explicit")
        val prefix = modelDir("prefix")

        val found = findTessdataDirectory(
            sources(
                explicitPath = explicit.path,
                bundledModel = bundled(MODEL),
                environment = mapOf("TESSDATA_PREFIX" to prefix.path),
            ),
        )

        assertSameDir(explicit, found)
    }

    @Test
    fun anExplicitPathWithoutAModel_findsNothingRatherThanFallingBack() {
        val empty = File(root, "empty").apply { mkdirs() }
        val prefix = modelDir("prefix")

        val found = findTessdataDirectory(
            sources(explicitPath = empty.path, environment = mapOf("TESSDATA_PREFIX" to prefix.path)),
        )

        assertNull(found, "an explicit path is the integrator's choice; another model must not replace it")
    }

    @Test
    fun aPackagedModelWithThePinnedHash_isExtractedToTheCacheDirAndUsed() {
        val prefix = modelDir("prefix")

        val found = findTessdataDirectory(
            sources(bundledModel = bundled(MODEL), environment = mapOf("TESSDATA_PREFIX" to prefix.path)),
        )

        assertNotNull(found, "the packaged model was not used")
        val dir = File(found).canonicalFile
        assertEquals(true, dir.startsWith(cacheDir.canonicalFile), "extracted to $dir, outside the cache dir")
        assertContentEquals(MODEL, File(dir, "eng.traineddata").readBytes())
    }

    @Test
    fun aPackagedModelWhoseBytesDoNotMatchThePin_isRejected() {
        val prefix = modelDir("prefix")
        val tampered = BundledModel(sha256 = sha256(MODEL), open = { PLANTED_MODEL.inputStream() })

        val found = findTessdataDirectory(
            sources(bundledModel = tampered, environment = mapOf("TESSDATA_PREFIX" to prefix.path)),
        )

        assertSameDir(prefix, found)
    }

    @Test
    fun aTamperedExtractedModel_isReplacedWithTheVerifiedBytes() {
        val first = findTessdataDirectory(sources(bundledModel = bundled(MODEL)))
        assertNotNull(first, "the packaged model was not used")
        File(first, "eng.traineddata").writeBytes(PLANTED_MODEL)

        val second = findTessdataDirectory(sources(bundledModel = bundled(MODEL)))

        assertNotNull(second, "the packaged model was not used after the cache was changed")
        assertContentEquals(MODEL, File(second, "eng.traineddata").readBytes())
    }

    @Test
    fun aModelPackagedAtThe2_0Path_isUsedWithoutAWarning() {
        val warnings = capturingWarnings {
            val found = findTessdataDirectory(sources(bundledModel = packagedAt(LOWERCASE_RESOURCE)))

            assertNotNull(found, "a model packaged at $LOWERCASE_RESOURCE was not used")
            assertContentEquals(MODEL, File(found, "eng.traineddata").readBytes())
        }

        assertEquals(emptyList(), warnings, "a model at the 2.0 path needs no warning")
    }

    @Test
    fun aModelPackagedOnlyAtThe1xPath_isStillUsedAndWarnsOnce() {
        val warnings = capturingWarnings {
            val found = findTessdataDirectory(sources(bundledModel = packagedAt(LEGACY_RESOURCE)))

            assertNotNull(found, "a model packaged at the 1.x path $LEGACY_RESOURCE was not used")
            assertContentEquals(MODEL, File(found, "eng.traineddata").readBytes())
        }

        assertEquals(1, warnings.size, "one warning to move the model, got $warnings")
    }

    @Test
    fun aModelPackagedAtBothPaths_isReadFromThe2_0Path() {
        val opened = mutableListOf<String>()
        val resources = mapOf(LOWERCASE_RESOURCE to MODEL, LEGACY_RESOURCE to PLANTED_MODEL)

        val warnings = capturingWarnings {
            val found = findTessdataDirectory(
                sources(
                    bundledModel = BundledModel(sha256(MODEL)) {
                        openBundledModel { path -> resources[path]?.also { opened += path }?.inputStream() }
                    },
                ),
            )
            assertNotNull(found, "the model at the 2.0 path was not used")
        }

        assertEquals(listOf(LOWERCASE_RESOURCE), opened)
        assertEquals(emptyList(), warnings)
    }

    /**
     * `camerak-ocr-tessdata-eng` is the model's own artifact: a jar holding the pinned model at the 2.0
     * path. On the classpath, the lookup a process makes extracts it and uses it.
     */
    @Test
    fun theTessdataArtifactsJar_isThePackagedModel() {
        val resource = OCRProcessor::class.java.getResource(BUNDLED_MODEL_RESOURCE)
        assertNotNull(resource, "$BUNDLED_MODEL_RESOURCE is not on the desktop test classpath")
        assertEquals("jar", resource.protocol, "the model is not read from the artifact's jar: $resource")

        val packaged = processTessdataSources(OcrConfig()).bundledModel
        val found = findTessdataDirectory(sources(bundledModel = packaged))

        assertNotNull(found, "the model in the jar was not used")
        assertEquals(BUNDLED_MODEL_SHA256, sha256(File(found, "eng.traineddata").readBytes()))
    }

    @Test
    fun noSource_findsNothing() {
        assertNull(findTessdataDirectory(sources()))
    }

    private fun sources(
        explicitPath: String? = null,
        bundledModel: BundledModel? = null,
        environment: Map<String, String> = emptyMap(),
    ) = TessdataSources(explicitPath, bundledModel, cacheDir, environment::get)

    private fun packagedAt(resource: String) =
        BundledModel(sha256(MODEL)) { openBundledModel { path -> if (path == resource) MODEL.inputStream() else null } }

    // The warnings logged while [block] runs.
    private fun capturingWarnings(block: () -> Unit): List<String> {
        val warnings = mutableListOf<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
        try {
            block()
        } finally {
            CameraKLogger.sink = null
            CameraKLogger.enabled = false
        }
        return warnings
    }

    private fun bundled(bytes: ByteArray) = BundledModel(sha256 = sha256(bytes), open = { bytes.inputStream() })

    private fun modelDir(name: String): File = File(root, name).apply {
        mkdirs()
        File(this, "eng.traineddata").writeBytes(MODEL)
    }

    private fun assertSameDir(expected: File, actual: String?) {
        assertNotNull(actual, "expected $expected, found nothing")
        assertEquals(expected.canonicalFile, File(actual).canonicalFile)
    }

    private companion object {
        val MODEL = ByteArray(4096) { (it * 31 % 251).toByte() }
        val PLANTED_MODEL = "planted".encodeToByteArray()
        const val LOWERCASE_RESOURCE = "/com/kashif/ocrplugin/tessdata/eng.traineddata"
        const val LEGACY_RESOURCE = "/com/kashif/ocrPlugin/tessdata/eng.traineddata"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
