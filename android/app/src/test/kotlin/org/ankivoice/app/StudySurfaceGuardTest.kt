package org.ankivoice.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AV-050's guard on the study surface itself, so the banner cannot come back by accident.
 *
 * [assertNoAutomaticGradingText] covers what the controller *publishes*; this covers what
 * the screen writes for itself. A composable's own literals never reach a [StudyState], so
 * a reinstated "Automatic grading is on" card would pass every snapshot assertion in the
 * suite and still be on screen for the whole session. The check is over the source because
 * that is where such a literal would live, and it is exact about what it excludes: KDoc and
 * comments may discuss the rule — the file's own documentation does — while no string the
 * screen can render may state it.
 */
class StudySurfaceGuardTest {

    private val screen = File("src/main/kotlin/org/ankivoice/app/StudyScreen.kt")

    @Test
    fun `no string literal in the study screen names the grading mode`() {
        assertTrue(screen.isFile, "StudyScreen.kt was not where this guard looks: ${screen.absolutePath}")
        val offending = literalsIn(screen.readText())
            .filter { it.contains("Automatic grading", ignoreCase = true) }
        assertTrue(
            offending.isEmpty(),
            "the study screen names the grading mode in its own text: $offending",
        )
    }

    /** The two composables AV-050 deleted, named so a revert is a failing test and not a review note. */
    @Test
    fun `the banner and the countdown are gone from the study screen`() {
        val code = screen.readText().replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " ")
        listOf("AutomaticGradingBanner", "AutomaticGradingPanel", "Keep it manual").forEach { gone ->
            assertFalse(code.contains(gone), "$gone came back to the study screen")
        }
    }

    /**
     * Every double-quoted literal in [source], with comments removed first so a rule stated
     * in KDoc is never mistaken for a rule stated on screen. Escaped quotes end nothing;
     * Kotlin's `$` interpolation is left in place, because a literal's fixed words are what
     * this guard is about.
     */
    private fun literalsIn(source: String): List<String> =
        LITERAL.findAll(source.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " "))
            .map { it.value.trim('"') }
            .toList()

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val LITERAL = Regex(""""(\\.|[^"\\])*"""")
    }
}
