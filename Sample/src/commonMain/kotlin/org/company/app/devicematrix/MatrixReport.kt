package org.company.app.devicematrix

/** One case's result, as the report keeps it. */
internal class MatrixRecord(
    val index: Int,
    val id: String,
    val section: String,
    val params: List<Pair<String, String>>,
    val verdict: Verdict,
    val reason: String?,
    val measures: List<Pair<String, Any?>>,
)

/** The file operations the report and the checks need; [PlatformFiles] on a device. */
internal interface MatrixFiles {
    fun exists(path: String): Boolean

    fun size(path: String): Long?

    fun readText(path: String): String?

    fun writeText(path: String, text: String)

    fun appendText(path: String, text: String)

    /** Up to [length] bytes from [offset]; fewer at the end of the file. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray

    fun delete(path: String)

    fun createDirectories(path: String)
}

/** [value] as a JSON value: null, a boolean, a finite number, a string, a list or a list of pairs as an object. */
internal fun jsonValue(value: Any?): String = TODO()

internal fun MatrixRecord.toJson(): String = TODO()

internal fun MatrixRecord.toMarkdownRow(): String = TODO()

/**
 * Writes `report.json` and `report.md` in [directory] as the run goes, so a crash keeps every case
 * finished before it. report.json is one JSON array: a header object, one object per case, and a
 * summary object; until [finish] it lacks only its closing bracket. A resumed run appends to it.
 */
internal class ReportWriter(private val files: MatrixFiles, private val directory: String) {
    val jsonPath: String = "$directory/report.json"
    val markdownPath: String = "$directory/report.md"

    fun begin(resume: Boolean, header: List<Pair<String, Any?>>): Unit = TODO()

    fun append(record: MatrixRecord): Unit = TODO()

    fun finish(summary: List<Pair<String, Any?>>): Unit = TODO()
}

/** [MatrixFiles] over this platform's file system. */
internal expect object PlatformFiles : MatrixFiles
