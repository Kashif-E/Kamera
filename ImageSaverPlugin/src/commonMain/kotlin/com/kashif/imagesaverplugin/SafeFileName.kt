package com.kashif.imagesaverplugin

/**
 * Returns [name] if it is safe to use as one file or folder name: 1 to 64 characters from
 * `A-Z a-z 0-9 . _ -`, and not `.` or `..`. Anything else (a path separator, a control character,
 * an empty or overlong name) throws [IllegalArgumentException], so a name a consumer forwards from
 * user input can never point outside the folder it is resolved in.
 *
 * The message names [what] but never repeats the rejected value, which may be user input.
 */
internal fun requireSafeFileName(name: String, what: String): String {
    require(SAFE_FILE_NAME.matches(name) && name != "." && name != "..") {
        "$what must be 1 to 64 characters from A-Z, a-z, 0-9, '.', '_' and '-', and not '.' or '..'"
    }
    return name
}

private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,64}")
