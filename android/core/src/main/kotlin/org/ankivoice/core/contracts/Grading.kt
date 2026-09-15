package org.ankivoice.core.contracts

enum class GradeLabel(val specName: String) {
    CORRECT("correct"),
    PARTIAL("partial"),
    INCORRECT("incorrect"),
    UNCERTAIN("uncertain"),
    ;

    /**
     * The rating this label may *propose*, or null to ask the learner for a self-grade.
     *
     * AV-006 approved advisory suggestions only. The fixture schema's historical
     * `initial_auto_ratings` names do not authorize unattended writes; #19 evaluates
     * suggestion quality and does not waive explicit confirmation.
     */
    val automaticProposal: Int?
        get() = when (this) {
            CORRECT -> 3 // Good
            INCORRECT -> 1 // Again
            PARTIAL, UNCERTAIN -> null
        }
}

/** A grader reply's payload: an advisory label, or a failure that is never a rating. */
sealed interface GradingOutcome

data class GradingResult(
    val label: GradeLabel,
    val reason: String,
) : GradingOutcome {
    /** Null means the learner must supply the rating; it never means Again. */
    fun proposedRating(permitted: List<Int>): Int? = label.automaticProposal?.takeIf { it in permitted }
}
