package com.kashif.cameraK.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * mkdocs.yml's `nav` and the pages under docs/ agree: every page the nav names exists, which
 * `mkdocs build --strict` in CI's docs workflow needs, and every page is in the nav or listed in
 * `not_in_nav`, so no page is published unreachable by accident. The theme's logo and favicon,
 * which `--strict` does not check, are files under docs/. docs/superpowers/ (design notes) is not a
 * site page and is not read.
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

    @Test
    fun theThemeLogoAndFaviconAreFilesUnderDocs() {
        val root = DocsSources.repoRoot
        val assets = MkdocsNav.themeAssets(File(root, "mkdocs.yml").readText())
        assertTrue(assets.isNotEmpty(), "read no logo or favicon from mkdocs.yml's theme")
        val missing = assets.filterNot { (_, path) -> File(root, "docs/$path").isFile }
            .map { (key, path) -> "theme.$key names docs/$path, which does not exist" }
        assertTrue(missing.isEmpty(), "mkdocs.yml's theme names missing files:\n${missing.joinToString("\n")}")
    }

    @Test
    fun themeAssetsReadsTheThemesOwnLogoAndFaviconOnly() {
        assertEquals(
            listOf("logo" to "assets/logo.png", "favicon" to "assets/favicon.ico"),
            MkdocsNav.themeAssets(THEME_FIXTURE),
        )
    }

    private companion object {
        val THEME_FIXTURE = """
            site_name: Fixture
            theme:
              name: material
              logo: assets/logo.png
              favicon: "assets/favicon.ico"
              icon:
                logo: material/camera
              palette:
                - scheme: default
            extra:
              logo: assets/other.png
        """.trimIndent()

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
    fun pages(mkdocs: String): List<String> = block(mkdocs, "nav").mapNotNull { line ->
        NAV_ENTRY.matchEntire(line)?.groupValues?.get(1)?.trim('"', '\'')
    }.filterNot { "://" in it }

    /**
     * Each nav entry with no page among [pages] (by path from docs/), and each page that is in
     * neither the nav nor `not_in_nav`.
     */
    fun findings(mkdocs: String, pages: Set<String>): List<String> {
        val nav = pages(mkdocs)
        val excluded = block(mkdocs, "not_in_nav").map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        val missing = nav.filterNot { it in pages }.map { "mkdocs.yml's nav names $it, which is not under docs/" }
        val unlisted = pages.sorted()
            .filterNot { page -> page in nav || excluded.any { matches(it, page) } }
            .map { "docs/$it is in neither mkdocs.yml's nav nor its not_in_nav" }
        return missing + unlisted
    }

    /**
     * The theme's `logo` and `favicon` file paths, by path from docs/, as (key, path). The keys of
     * the theme's own level only: `icon.logo` names an icon, not a file.
     */
    fun themeAssets(mkdocs: String): List<Pair<String, String>> {
        val theme = block(mkdocs, "theme").filter(String::isNotBlank)
        val indent = theme.firstOrNull()?.takeWhile(Char::isWhitespace) ?: return emptyList()
        return theme.mapNotNull { line ->
            Regex("""^${Regex.escape(indent)}(logo|favicon):\s*(\S+)\s*$""").matchEntire(line)
                ?.let { it.groupValues[1] to it.groupValues[2].trim('"', '\'') }
        }
    }

    /** The indented lines under the top-level [key], up to the next top-level key. */
    private fun block(mkdocs: String, key: String): List<String> {
        val lines = mkdocs.lines()
        val from = lines.indexOfFirst { Regex("""^${Regex.escape(key)}:.*""").matches(it) }
        if (from < 0) return emptyList()
        return lines.drop(from + 1).takeWhile { it.isBlank() || it.first().isWhitespace() || it.startsWith("-") }
    }

    /**
     * Whether the `not_in_nav` [pattern] matches [page], as mkdocs reads it (gitignore style): `*`
     * within one directory, `**` across them, a trailing `/` for everything below, and a leading `/`
     * anchoring at docs/, without which a pattern with no `/` matches a file name at any depth.
     */
    private fun matches(pattern: String, page: String): Boolean {
        val anchored = pattern.startsWith("/")
        val path = pattern.removePrefix("/").let { if (it.endsWith("/")) "$it**" else it }
        val regex = Regex(
            path.split("**").joinToString(".*") { part ->
                part.split("*").joinToString("[^/]*") { if (it.isEmpty()) "" else Regex.escape(it) }
            },
        )
        return regex.matches(page) || (!anchored && '/' !in path && regex.matches(page.substringAfterLast('/')))
    }

    /**
     * One nav line: `- Title: page.md` or a bare `- page.md`. A section title (`- Guides:`) ends in
     * its colon and has no value.
     */
    private val NAV_ENTRY = Regex("""^\s*-\s+(?:[^:]+:\s+)?(\S*[^\s:])\s*$""")
}
