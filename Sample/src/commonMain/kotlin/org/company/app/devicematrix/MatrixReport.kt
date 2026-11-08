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

/** The file operations the report and the checks need; [platformFiles] on a device. */
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
internal fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Float -> if (value.isFinite()) value.toString() else "null"
    is Enum<*> -> jsonString(value.name)
    is Measures -> jsonObject(value.fields)
    is Map<*, *> -> jsonObject(value.entries.map { it.key.toString() to it.value })
    is List<*> -> if (value.isNotEmpty() && value.all { it is Pair<*, *> && it.first is String }) {
        jsonObject(value.map { it as Pair<*, *> }.map { it.first as String to it.second })
    } else {
        value.joinToString(",", "[", "]") { jsonValue(it) }
    }
    else -> jsonString(value.toString())
}

internal fun jsonObject(fields: List<Pair<String, Any?>>): String =
    fields.joinToString(",", "{", "}") { (key, value) -> jsonString(key) + ":" + jsonValue(value) }

private fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}

internal fun MatrixRecord.toJson(): String = jsonObject(
    listOf(
        "index" to index,
        "id" to id,
        "section" to section,
        "params" to params,
        "verdict" to verdict.label,
        "reason" to reason,
        "measures" to Measures(measures),
    ),
)

// Measures are an object even when empty; a bare empty list would print as an array.
private class Measures(val fields: List<Pair<String, Any?>>)

internal fun MatrixRecord.toMarkdownRow(): String = listOf(
    index.toString(),
    verdict.label,
    id,
    reason.orEmpty(),
    measures.joinToString(", ") {
        "${it.first}=${markdownValue(it.second)}"
    },
)
    .joinToString(" | ", "| ", " |") { it.replace("|", "\\|").replace('\n', ' ') }

private fun markdownValue(value: Any?): String = when (value) {
    is List<*> -> if (value.isNotEmpty() && value.all { it is Pair<*, *> }) {
        value.joinToString(", ", "{", "}") { (it as Pair<*, *>).let { (k, v) -> "$k=${markdownValue(v)}" } }
    } else {
        value.joinToString(", ", "[", "]") { markdownValue(it) }
    }
    else -> value.toString()
}

/**
 * Writes `report.json` and `report.md` in [directory] as the run goes, so a crash keeps every case
 * finished before it. report.json is one JSON array: a header object, one object per case, and a
 * summary object; until [finish] it lacks only its closing bracket. A resumed run appends to it.
 */
internal class ReportWriter(private val files: MatrixFiles, private val directory: String) {
    val jsonPath: String = "$directory/report.json"
    val markdownPath: String = "$directory/report.md"

    fun begin(resume: Boolean, header: List<Pair<String, Any?>>) {
        files.createDirectories(directory)
        val element = "{\"header\":" + jsonObject(header) + "}"
        val lines = header.joinToString("\n", postfix = "\n") { "- ${it.first}: ${markdownValue(it.second)}" }
        val existing = if (resume) files.readText(jsonPath) else null
        if (existing == null) {
            files.writeText(jsonPath, "[\n$element")
            files.writeText(markdownPath, "# CameraK device matrix\n\n$lines\n$TABLE_HEADER")
        } else {
            // A finished report is reopened: its closing bracket goes, and the resume's header is the next element.
            val open = existing.trimEnd().removeSuffix("]").trimEnd()
            files.writeText(jsonPath, "$open\n,$element")
            files.appendText(markdownPath, "\n## Resumed\n\n$lines\n$TABLE_HEADER")
        }
    }

    fun append(record: MatrixRecord) {
        files.appendText(jsonPath, "\n," + record.toJson())
        files.appendText(markdownPath, record.toMarkdownRow() + "\n")
    }

    fun finish(summary: List<Pair<String, Any?>>) {
        files.appendText(jsonPath, "\n,{\"summary\":" + jsonObject(summary) + "}\n]\n")
        files.appendText(
            markdownPath,
            "\n## Summary\n\n" +
                summary.joinToString("\n", postfix = "\n") { "- ${it.first}: ${markdownValue(it.second)}" },
        )
    }

    private companion object {
        const val TABLE_HEADER = "| # | verdict | case | reason | measures |\n|---|---|---|---|---|\n"
    }
}

/** [MatrixFiles] over this platform's file system. */
internal expect val platformFiles: MatrixFiles
