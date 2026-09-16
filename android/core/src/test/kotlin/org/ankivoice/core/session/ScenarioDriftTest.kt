package org.ankivoice.core.session

import org.ankivoice.core.contracts.ALL_FAILURE_MODES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drift guard for the ported conformance suite, in the manner of AV-041's
 * ManifestDriftTest.
 *
 * `tools/av013_scenarios.py` derives the manifest from `tools/av007_scenarios.py` and
 * `tests/test_av013_session.py` keeps the checked-in copy current; this test fails when
 * the Kotlin suite stops matching it. A scenario cannot be dropped, renamed or quietly
 * left unported without one of the two failing.
 */
class ScenarioDriftTest {
    private val records: List<List<String>> =
        checkNotNull(javaClass.getResource("/av007/scenarios.txt")) { "missing av007/scenarios.txt" }
            .readText()
            .lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }

    private fun records(kind: String): List<List<String>> =
        records.filter { it[0] == kind }.map { it.drop(1) }

    /** Every `@Scenario` name the ported named suite declares, in declaration order. */
    private fun portedNames(): List<String> =
        NamedScenariosTest::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(Scenario::class.java)?.name }
            .sorted()

    @Test
    fun `the manifest describes the whole 53-scenario suite`() {
        assertEquals(19, records("named").size)
        assertEquals(34, records("failure").size)
        assertEquals(53, records("named").size + records("failure").size)
    }

    @Test
    fun `every named scenario in the binding is ported`() {
        val expected = records("named").map { it[0] }.sorted()
        assertEquals(expected, portedNames())
    }

    @Test
    fun `no ported scenario is declared twice`() {
        val ported = portedNames()
        assertEquals(ported.size, ported.toSet().size, "duplicate @Scenario names: $ported")
    }

    @Test
    fun `the failure sweep covers the binding's taxonomy, in order`() {
        val expected = records("failure").map { "${it[0]}.${it[1]}" }
        assertEquals(expected, ALL_FAILURE_MODES.map { "${it.contract.name}.${it.specName}" })
    }

    @Test
    fun `every binding session state is reachable through a Kotlin state`() {
        val expected = records("sessionState").map { it[0] }
        assertEquals(expected, BindingSessionState.entries.map { it.specName })
        val covered = SessionState.entries.mapNotNull { it.binding }.toSet()
        assertEquals(BindingSessionState.entries.toSet(), covered, "a binding state has no Kotlin state")
    }

    @Test
    fun `the interruption kinds match the binding`() {
        assertEquals(records("interruption").map { it[0] }, Interruption.entries.map { it.specName })
    }

    @Test
    fun `the Kotlin states AV-013 adds are explicit and documented as halts`() {
        // The six AV-013 requires: pause, interruption, retry, unsupported card, reveal
        // and outcome-unknown. Each is a state of its own, not a flag on another one.
        val added = setOf(
            SessionState.PAUSED,
            SessionState.INTERRUPTED,
            SessionState.RETRYING,
            SessionState.UNSUPPORTED,
            SessionState.REVEALING,
            SessionState.OUTCOME_UNKNOWN,
        )
        assertTrue(added.all { it in SessionState.entries }, "AV-013 must model all six explicitly")
        assertEquals(
            setOf(SessionState.PAUSED, SessionState.INTERRUPTED, SessionState.UNSUPPORTED, SessionState.OUTCOME_UNKNOWN),
            added.filter { it.isHalted }.toSet(),
        )
        assertEquals(
            HALTED_STATES,
            setOf(
                SessionState.PAUSED,
                SessionState.OUTCOME_UNKNOWN,
                SessionState.INTERRUPTED,
                SessionState.UNSUPPORTED,
                SessionState.STOPPED,
                SessionState.EXHAUSTED,
            ),
        )
    }
}
