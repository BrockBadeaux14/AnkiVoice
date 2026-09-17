package org.ankivoice.provider

/**
 * AV-043: the two grading transports, in the order they are tried.
 *
 * Free first. The paid route is tried only after the free route is refused by its guard,
 * unavailable, past #18's deadline or failed, and only within the owner's daily USD cap.
 * A paid request is never sent while the free route would have been tried. Neither route
 * decides a grade or reaches a writer; both carry #18's instruction and reply validation.
 */
enum class GradingRoute(val specName: String) {
    /** AV-006's pinned free endpoint at a verified zero cost. */
    FREE("free"),

    /** The pinned paid endpoint, charged to the owner's OpenRouter credits within the cap. */
    PAID("paid"),
    ;

    companion object {
        /** The order the semantic grader tries the routes in. */
        val ORDER: List<GradingRoute> = listOf(FREE, PAID)
    }
}
