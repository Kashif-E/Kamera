package com.kashif.cameraK.docs

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plugins guide says how an app chooses where OCR's models come from. On Android, `camerak-ocr`
 * depends on ML Kit's bundled recognizer, and an app can swap it for the Play Services one, which
 * downloads its model on first use. On desktop, Tesseract's English model is its own artifact,
 * `camerak-ocr-tessdata-eng`, which `camerak-ocr` does not depend on: an app opts in to it.
 */
class OcrPackagingDocsTest {
    private val guide = "docs/guides/plugins.md"
    private val ocrSection get() = DocsSources.section(guide, Regex("## OCR Plugin"), Regex("## .*"))
    private val desktopData get() = DocsSources.section(guide, Regex("### Desktop language data"), Regex("#{1,3} .*"))

    @Test
    fun unbundledMlKitSwapNamesBothCoordinatesAndTheExclude() {
        val code = fencedBlocks(ocrSection).joinToString("\n")
        val statements = DocsSources.statements(ocrSection)
        val download = statements.filter { "download" in it && "recognize" in it }
        val problems = listOfNotNull(
            "$guide's OCR section shows no exclude of the bundled $BUNDLED"
                .takeIf { !EXCLUDE_BUNDLED.containsMatchIn(code) },
            "$guide's OCR section adds no $UNBUNDLED dependency"
                .takeIf { !Regex("""implementation\("${Regex.escape(UNBUNDLED)}:[^"]+"\)""").containsMatchIn(code) },
            "$guide's OCR section does not name the bundled default $BUNDLED"
                .takeIf { statements.none { BUNDLED in it } },
            "$guide's OCR section does not say what recognize returns while the model downloads: $download"
                .takeIf { download.none { "Failure(Internal)" in it } },
        )
        assertTrue(problems.isEmpty(), "The unbundled ML Kit swap is not documented:\n${problems.joinToString("\n")}")
    }

    @Test
    fun desktopLanguageDataNamesTheTessdataArtifactAsTheOptIn() {
        val named = DocsSources.statements(desktopData).filter { TESSDATA_ARTIFACT in it }
        val problems = listOfNotNull(
            "\"Desktop language data\" does not name $TESSDATA_ARTIFACT".takeIf { named.isEmpty() },
            "\"Desktop language data\" does not say camerak-ocr leaves $TESSDATA_ARTIFACT out: $named"
                .takeIf { named.none { OPT_IN.containsMatchIn(it) } },
        )
        assertTrue(problems.isEmpty(), "The desktop model artifact is not documented:\n${problems.joinToString("\n")}")
    }

    private fun fencedBlocks(section: String): List<String> = FENCE.findAll(section).map { it.groupValues[1] }.toList()

    private companion object {
        const val BUNDLED = "com.google.mlkit:text-recognition"
        const val UNBUNDLED = "com.google.android.gms:play-services-mlkit-text-recognition"
        const val TESSDATA_ARTIFACT = "camerak-ocr-tessdata-eng"
        val EXCLUDE_BUNDLED =
            Regex("""exclude\(\s*group\s*=\s*"com\.google\.mlkit",\s*module\s*=\s*"text-recognition"\s*\)""")
        val FENCE = Regex("```[A-Za-z]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val OPT_IN = Regex("""`camerak-ocr` does not depend on|opt-in|opt in""")
    }
}
