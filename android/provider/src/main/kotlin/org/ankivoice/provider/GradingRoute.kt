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
        /**
         * The order the semantic grader tries the routes in.
         *
         * **Reversed September 17, 2026 at the owner's direction (AV-050 D.6).** It was
         * free-then-paid, so a request only reached the owner's credits after the free route
         * refused it. It is now **paid first, free as the backup**.
         *
         * This is a deliberate reversal of AV-043's cost posture, not an oversight. Every AI
         * grading request now spends the owner's OpenRouter credits by default, and the free
         * route is what catches a paid request that was refused, unavailable, over budget or
         * failed. The daily paid budget and the per-request hold are unchanged and still
         * bound the spend: what changed is which route the budget is spent on first.
         */
        val ORDER: List<GradingRoute> = listOf(PAID, FREE)
    }
}
