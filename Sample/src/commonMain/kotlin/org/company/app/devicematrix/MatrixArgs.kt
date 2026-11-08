package org.company.app.devicematrix

/**
 * How a matrix run was asked for: start at case [offset] (a resume after a crash at that case), run
 * only [sections], and stop after [limit] cases when it is set.
 */
internal data class MatrixArgs(
    val offset: Int = 0,
    val sections: Set<MatrixSection> = MatrixSection.entries.toSet(),
    val limit: Int? = null,
    /** Instead of the matrix: open and close this many holders of [churnLens] and [churnType] in one process. */
    val churn: Int? = null,
    val churnLens: String = "BACK",
    val churnType: String = "WIDE_ANGLE",
)

/**
 * The run iOS launch [arguments] ask for, or null without `-cameraKMatrix`:
 * `-cameraKMatrix [-cameraKMatrixOffset N] [-cameraKMatrixSections photo,video] [-cameraKMatrixLimit N]`.
 */
internal fun matrixArgsFromLaunchArguments(arguments: List<String>): MatrixArgs? {
    if ("-cameraKMatrix" !in arguments) return null
    fun valueOf(flag: String): String? = arguments.indexOf(flag).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    return MatrixArgs(
        offset = valueOf("-cameraKMatrixOffset")?.toIntOrNull() ?: 0,
        sections = parseSections(valueOf("-cameraKMatrixSections")),
        limit = valueOf("-cameraKMatrixLimit")?.toIntOrNull(),
        churn = valueOf("-cameraKMatrixChurn")?.toIntOrNull(),
        churnLens = valueOf("-cameraKMatrixChurnLens") ?: "BACK",
        churnType = valueOf("-cameraKMatrixChurnType") ?: "WIDE_ANGLE",
    )
}

/** The sections a comma-separated list names; every section for null or blank. Unknown names are ignored. */
internal fun parseSections(csv: String?): Set<MatrixSection> =
    csv?.split(',')?.mapNotNull(MatrixSection::ofKey)?.toSet()?.takeIf { it.isNotEmpty() }
        ?: MatrixSection.entries.toSet()
