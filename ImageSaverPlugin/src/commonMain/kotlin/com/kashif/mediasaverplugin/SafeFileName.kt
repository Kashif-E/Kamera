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

/**
 * Throws [IllegalArgumentException] unless an auto-save name built from [prefix],
 * `<prefix>_<epochMillis>`, is a safe file name for every capture time until the year 2286: [prefix]
 * is at most 50 characters from `A-Z a-z 0-9 . _ -`. The message never repeats the prefix.
 */
internal fun requireAutoSavePrefix(prefix: String) {
    require(SAFE_FILE_NAME.matches(autoSaveImageName(prefix, LONGEST_EPOCH_MILLIS))) {
        "prefix must be at most 50 characters from A-Z, a-z, 0-9, '.', '_' and '-'"
    }
}

// The largest 13-digit epoch milliseconds; a capture before the year 2286 has no more digits.
private const val LONGEST_EPOCH_MILLIS = 9_999_999_999_999L
