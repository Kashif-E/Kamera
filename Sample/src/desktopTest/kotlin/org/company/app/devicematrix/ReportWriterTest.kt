package org.company.app.devicematrix

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The report is written as the run goes, so a crash keeps every finished case, and a resume appends. */
class ReportWriterTest {
    private val directory = kotlin.io.path.createTempDirectory("matrix-report").toFile()
    private val writer = ReportWriter(PlatformFiles, directory.path)
    private val json get() = File(directory, "report.json").readText()
    private val markdown get() = File(directory, "report.md").readText()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun record(index: Int, verdict: Verdict = Verdict.PASS, reason: String? = null) = MatrixRecord(
        index = index,
        id = "photo/BACK/$index",
        section = "photo",
        params = listOf("lens" to "BACK", "imageFormat" to "JPEG"),
        verdict = verdict,
        reason = reason,
        measures = listOf("readyMs" to 412L, "size" to listOf("w" to 4032, "h" to 3024), "fps" to 29.5, "file" to null),
    )

    @Test
    fun jsonValuesAreEscaped() {
        assertEquals("null", jsonValue(null))
        assertEquals("true", jsonValue(true))
        assertEquals("12", jsonValue(12))
        assertEquals("1.5", jsonValue(1.5))
        assertEquals("null", jsonValue(Double.NaN), "JSON has no NaN")
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", jsonValue("a\"b\\c\nd\u0001"))
        assertEquals("[1,\"x\"]", jsonValue(listOf(1, "x")))
        assertEquals("{\"k\":2}", jsonValue(listOf("k" to 2)))
    }

    @Test
    fun aRecordIsOneJsonObject_andOneMarkdownRow() {
        assertEquals(
            "{\"index\":3,\"id\":\"photo/BACK/3\",\"section\":\"photo\"," +
                "\"params\":{\"lens\":\"BACK\",\"imageFormat\":\"JPEG\"},\"verdict\":\"FAIL\",\"reason\":\"ratio | off\"," +
                "\"measures\":{\"readyMs\":412,\"size\":{\"w\":4032,\"h\":3024},\"fps\":29.5,\"file\":null}}",
            record(3, Verdict.FAIL, "ratio | off").toJson(),
        )
        assertEquals(
            "| 3 | FAIL | photo/BACK/3 | ratio \\| off | readyMs=412, size={w=4032, h=3024}, fps=29.5, file=null |",
            record(3, Verdict.FAIL, "ratio | off").toMarkdownRow(),
        )
    }

    @Test
    fun eachCaseIsAppendedAsItFinishes_soAPartialReportLacksOnlyTheClosingBracket() {
        writer.begin(resume = false, header = listOf("device" to "Pixel 7"))
        writer.append(record(0))
        writer.append(record(1, Verdict.UNSUPPORTED_OK, "start: Unsupported(IMAGE_FORMAT)"))

        val partial = json
        assertTrue(partial.startsWith("[\n{\"header\":{\"device\":\"Pixel 7\"}}"), partial)
        assertFalse(partial.trimEnd().endsWith("]"), "an unfinished report is not closed")
        assertEquals(2, partial.lines().count { it.startsWith(",{\"index\":") })
        assertEquals(2, markdown.lines().count { it.startsWith("| 0 |") || it.startsWith("| 1 |") })

        writer.finish(listOf("PASS" to 1, "UNSUPPORTED-OK" to 1))
        assertTrue(json.endsWith("\n,{\"summary\":{\"PASS\":1,\"UNSUPPORTED-OK\":1}}\n]\n"), json)
        assertTrue("PASS" in markdown.substringAfterLast("## Summary"), markdown)
    }

    @Test
    fun aResumeAppendsToTheReport_reopeningAFinishedOne() {
        writer.begin(resume = false, header = listOf("device" to "iPhone"))
        writer.append(record(0))
        writer.finish(listOf("PASS" to 1))

        val resumed = ReportWriter(PlatformFiles, directory.path)
        resumed.begin(resume = true, header = listOf("resumedAt" to 1))
        resumed.append(record(1))
        resumed.finish(listOf("PASS" to 1))

        val text = json
        assertEquals(1, Regex("""^\[""", RegexOption.MULTILINE).findAll(text).count(), "one array")
        assertEquals(1, Regex("""^]""", RegexOption.MULTILINE).findAll(text).count(), "closed once")
        assertTrue(text.contains(",{\"index\":0,") && text.contains(",{\"index\":1,"), text)
        assertTrue(text.contains("\n,{\"header\":{\"resumedAt\":1}}"), "the resume's header is another element")
        assertTrue(markdown.contains("| 0 |") && markdown.contains("| 1 |"))
    }

    @Test
    fun aResumeWithNoReportStartsOne() {
        writer.begin(resume = true, header = listOf("device" to "iPhone"))
        writer.append(record(5))
        assertTrue(json.startsWith("[\n{\"header\":"), json)
    }
}
