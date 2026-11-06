package org.company.app

/**
 * Multiplatform-safe alternative to `String.format` for simple numeric formatting.
 *
 * Supports:
 * - `%.Nf` for fixed decimals
 * - `%f` for float string
 * - `%d` for integer
 */
fun formatString(format: String, value: Float): String {
    // Defensive check: if no placeholder exists, return format unchanged
    if (!format.contains("%")) {
        return format
    }

    val placeholderRegex = """%(\.(\d+))?[fd]""".toRegex()
    val matchResult = placeholderRegex.find(format) ?: return format

    val placeholder = matchResult.value
    val decimalPlaces = matchResult.groups[2]?.value?.toIntOrNull()

    val formattedValue =
        when {
            placeholder.contains(".") && placeholder.endsWith("f") && decimalPlaces != null -> {
                formatFloatWithDecimals(value, decimalPlaces)
            }
            placeholder == "%f" -> value.toString()
            placeholder == "%d" -> value.toInt().toString()
            else -> value.toString()
        }

    return format.replaceFirst(placeholder, formattedValue)
}

@Deprecated("Use formatString(format, value)")
fun String.format(format: String, value: Float): String = formatString(format, value)

/**
 * Helper function to format a Float with a specific number of decimal places.
 * Uses proper rounding, not truncation.
 *
 * @param value The Float to format
 * @param decimalPlaces Number of decimal places to include
 * @return Formatted string representation
 */
private fun formatFloatWithDecimals(value: Float, decimalPlaces: Int): String {
    require(decimalPlaces >= 0) { "Decimal places must be non-negative, got: $decimalPlaces" }

    // Handle edge case: 0 decimal places means integer
    if (decimalPlaces == 0) {
        // Round to nearest integer
        return kotlin.math
            .round(value)
            .toInt()
            .toString()
    }

    // Calculate the multiplier for rounding (e.g., 2 decimals = 100)
    val multiplier = 10.0.pow(decimalPlaces)

    // Round the value to the desired decimal places
    val rounded = kotlin.math.round(value * multiplier) / multiplier

    // Convert to string and ensure we have the exact number of decimal places
    val stringValue = rounded.toString()

    // Split on decimal point
    val parts = stringValue.split(".")
    val integerPart = parts[0]
    val decimalPart = if (parts.size > 1) parts[1] else ""

    // Pad or truncate decimal part to exact length needed
    val paddedDecimalPart = decimalPart.padEnd(decimalPlaces, '0').take(decimalPlaces)

    return "$integerPart.$paddedDecimalPart"
}

/**
 * Extension function for Double.pow() since it might not be available in all KMP contexts
 */
private fun Double.pow(n: Int): Double {
    require(n >= 0) { "Power must be non-negative for this implementation" }

    var result = 1.0
    repeat(n) {
        result *= this
    }
    return result
}
