package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * mkdocs.yml's `nav` and the pages under docs/ agree: every page the nav names exists, which
 * `mkdocs build --strict` in CI's docs workflow needs, and every page is in the nav or listed in
 * `not_in_nav`, so no page is published unreachable by accident. docs/superpowers/ (design notes)
 * is not a site page and is not read.
 */
class MkdocsNavTest {
    @Test
    fun everyNavEntryResolvesAndEveryPageIsInTheNav() {
        val root = DocsSources.repoRoot
        val mkdocs = File(root, "mkdocs.yml").readText()
        val docs = File(root, "docs")
        val pages = docs.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(docs).invariantSeparatorsPath }
            .filterNot { it.startsWith("superpowers/") }
            .toSet()
        assertTrue(MkdocsNav.pages(mkdocs).size > 1, "read no page from mkdocs.yml's nav")
        val findings = MkdocsNav.findings(mkdocs, pages)
        assertTrue(findings.isEmpty(), "mkdocs.yml and docs/ disagree:\n${findings.joinToString("\n")}")
    }

    @Test
    fun navPagesReadsNestedEntriesAndStopsAtTheNextKey() {
        assertEquals(
            listOf("index.md", "getting-started/installation.md", "migration/2.0.md"),
            MkdocsNav.pages(FIXTURE),
        )
    }

    @Test
    fun aNavEntryWithNoPageIsAFinding() {
        val findings = MkdocsNav.findings(FIXTURE, setOf("index.md", "getting-started/installation.md"))
        assertEquals(listOf("mkdocs.yml's nav names migration/2.0.md, which is not under docs/"), findings)
    }

    @Test
    fun aPageInNeitherTheNavNorNotInNavIsAFinding() {
        val pages = setOf(
            "index.md",
            "getting-started/installation.md",
            "migration/2.0.md",
            "orphan.md",
            "drafts/notes.md",
            "perf/ios.md",
        )
        assertEquals(
            listOf("docs/orphan.md is in neither mkdocs.yml's nav nor its not_in_nav"),
            MkdocsNav.findings(FIXTURE, pages),
        )
    }

    private companion object {
        val FIXTURE = """
            site_name: Fixture
            not_in_nav: |
              /drafts/*.md
              perf/
            nav:
              - Home: index.md
              - Getting Started:
                - Installation: getting-started/installation.md
              - GitHub: https://github.com/kashif-e/Kamera
              - migration/2.0.md
            extra:
              social:
                - icon: fontawesome/brands/github
                  link: https://github.com/kashif-e/Kamera
            """.trimIndent()
    }
}

/** What mkdocs.yml's `nav` and `not_in_nav` say about the pages under docs/. */
internal object MkdocsNav {
    /** The pages the `nav` names, by path from docs/, in order; links to other sites are left out. */
    fun pages(mkdocs: String): List<String> = emptyList()

    /**
     * Each nav entry with no page among [pages] (by path from docs/), and each page that is in
     * neither the nav nor `not_in_nav`.
     */
    fun findings(mkdocs: String, pages: Set<String>): List<String> = emptyList()
}
