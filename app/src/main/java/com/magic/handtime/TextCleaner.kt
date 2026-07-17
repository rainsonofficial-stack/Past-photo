package com.magic.handtime

object TextCleaner {

    fun clean(input: String): String {
        var result = input

        // Remove parentheses (both brackets)
        result = result.replace("(", "").replace(")", "")

        // Remove colon
        result = result.replace(":", "")

        // Replace the standalone word "and" with "&"
        result = Regex("\\band\\b", RegexOption.IGNORE_CASE).replace(result, "&")

        // Strip standalone words "movie" / "film"
        result = Regex("\\b(movie|film)\\b", RegexOption.IGNORE_CASE).replace(result, "")

        // Collapse any resulting double spaces and trim edges
        result = result.replace(Regex("\\s+"), " ").trim()

        return result
    }
}
