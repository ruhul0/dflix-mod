package com.dflixtv.app.data

/**
 * Helpers for pulling JSON values out of a Next.js React Server Components payload
 * (what the site returns when a page is requested with the `RSC: 1` header).
 *
 * The payload is a series of `id:JSON` lines. The data we need is embedded as props, so we
 * locate a known key sequence and slice out the balanced JSON value that follows it.
 */
object Rsc {

    /** Returns the JSON value (object or array) starting at [start], or null if unbalanced. */
    fun sliceValue(text: String, start: Int): String? {
        val open = text.getOrNull(start) ?: return null
        val close = when (open) {
            '{' -> '}'
            '[' -> ']'
            else -> return null
        }
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                    // Other bracket kinds are fine as long as they stay balanced inside the value.
                }
            }
            i++
        }
        return null
    }

    /** JSON value immediately after the first match of [marker] (marker must end right before `{` or `[`). */
    fun valueAfter(text: String, marker: String, from: Int = 0): String? {
        val idx = text.indexOf(marker, from)
        if (idx < 0) return null
        return sliceValue(text, idx + marker.length)
    }

    /** All values following each match of [regex], where the match ends right before `{` or `[`. */
    fun valuesAfter(text: String, regex: Regex): List<Pair<MatchResult, String>> =
        regex.findAll(text).mapNotNull { m -> sliceValue(text, m.range.last + 1)?.let { m to it } }.toList()

    /** The enclosing object whose body starts with [marker], e.g. `{"titleId":`. */
    fun objectStartingWith(text: String, marker: String): String? {
        val idx = text.indexOf(marker)
        if (idx < 0) return null
        return sliceValue(text, idx)
    }
}
