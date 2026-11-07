package org.company.app.devicematrix

/**
 * How a matrix run was asked for: start at case [offset] (a resume after a crash at that case), run
 * only [sections], and stop after [limit] cases when it is set.
 */
internal data class MatrixArgs(
    val offset: Int = 0,
    val sections: Set<MatrixSection> = MatrixSection.entries.toSet(),
    val limit: Int? = null,
)

/**
 * The run iOS launch [arguments] ask for, or null without `-cameraKMatrix`:
 * `-cameraKMatrix [-cameraKMatrixOffset N] [-cameraKMatrixSections photo,video] [-cameraKMatrixLimit N]`.
 */
internal fun matrixArgsFromLaunchArguments(arguments: List<String>): MatrixArgs? = TODO()

/** The sections a comma-separated list names; every section for null or blank. Unknown names are ignored. */
internal fun parseSections(csv: String?): Set<MatrixSection> = TODO()
