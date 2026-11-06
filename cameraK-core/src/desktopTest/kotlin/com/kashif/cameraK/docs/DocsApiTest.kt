package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The README and the pages under docs/ teach the 2.0 API. The migration guide, which has to name
 * the 1.x symbols and artifact ids it replaces, and docs/superpowers/ (design notes) are not read.
 */
class DocsApiTest {
    private val root = DocsSources.repoRoot

    /** README.MD and every page under docs/ that teaches the current API, by path from the root. */
    private val teachingDocs: List<String> by lazy {
        val docs = File(root, "docs").walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it.startsWith("docs/superpowers/") || it == MIGRATION_GUIDE }
            .sorted()
            .toList()
        listOf(DocsSources.README) + docs
    }

    @Test
    fun pluginCoordinatesAreThe2_0ArtifactIds() {
        val stale = teachingDocs.flatMap { doc ->
            DocsSources.lines(doc).withIndex().flatMap { (index, line) ->
                COORDINATE.findAll(line).map { it.groupValues[1] }
                    .filterNot { it in ARTIFACT_IDS }
                    .map { "$doc:${index + 1}: $it" }
            }
        }
        assertTrue(stale.isEmpty(), "artifact ids that 2.0 does not publish:\n${stale.joinToString("\n")}")

        val readmeIds = COORDINATE.findAll(File(root, DocsSources.README).readText()).map { it.groupValues[1] }.toSet()
        val missing = PLUGIN_ARTIFACT_IDS - readmeIds
        assertTrue(missing.isEmpty(), "${DocsSources.README} does not install $missing")
    }

    private companion object {
        const val MIGRATION_GUIDE = "docs/migration/2.0.md"

        val COORDINATE = Regex("""io\.github\.kashif-mehmood-km:([A-Za-z0-9_.-]+)""")

        /** §9.4: the plugins' 2.0 artifact ids. Their 1.x ids publish only relocation POMs. */
        val PLUGIN_ARTIFACT_IDS = setOf("camerak-media-saver", "camerak-qr-scanner", "camerak-ocr", "camerak-analyzer")

        val ARTIFACT_IDS = setOf("camerak", "camerak-core", "camerak-testing", "camerak-bom") + PLUGIN_ARTIFACT_IDS
    }
}
