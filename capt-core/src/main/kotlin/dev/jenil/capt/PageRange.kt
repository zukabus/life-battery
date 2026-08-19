package dev.jenil.capt

/**
 * Parses the page-range box.
 *
 * Accepts the usual forms: `1-3,5,8-`, spaces anywhere, open-ended ranges.
 * Pages are 1-based going in and 0-based coming out. Duplicates are kept
 * and order is preserved, so `3,1,3` really does print three sheets in
 * that order.
 */
object PageRange {

    /** @throws IllegalArgumentException with a message fit to show the user. */
    fun parse(spec: String, pageCount: Int): List<Int> {
        if (spec.isBlank()) return (0 until pageCount).toList()

        val result = mutableListOf<Int>()
        for (rawPart in spec.split(',')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue

            val dash = part.indexOf('-')
            if (dash < 0) {
                result += single(part, pageCount)
                continue
            }

            val fromText = part.substring(0, dash).trim()
            val toText = part.substring(dash + 1).trim()
            val from = if (fromText.isEmpty()) 1 else number(fromText)
            val to = if (toText.isEmpty()) pageCount else number(toText)

            require(from <= to) { "'$part' counts backwards" }
            require(from >= 1 && to <= pageCount) {
                "'$part' is outside the document (it has $pageCount page${plural(pageCount)})"
            }
            for (p in from..to) result += p - 1
        }
        require(result.isNotEmpty()) { "no pages selected" }
        return result
    }

    private fun single(text: String, pageCount: Int): Int {
        val n = number(text)
        require(n in 1..pageCount) {
            "page $n does not exist (the document has $pageCount page${plural(pageCount)})"
        }
        return n - 1
    }

    private fun number(text: String): Int =
        text.toIntOrNull() ?: throw IllegalArgumentException("'$text' is not a page number")

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
