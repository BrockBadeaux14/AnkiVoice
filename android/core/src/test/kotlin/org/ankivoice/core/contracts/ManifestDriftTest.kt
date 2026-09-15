package org.ankivoice.core.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Drift guard: :core must match the manifest that tools/av041_manifest.py derives from
 * the Python binding. tests/test_av041_android.py keeps the manifest itself current.
 */
class ManifestDriftTest {
    private val records: List<List<String>> =
        checkNotNull(javaClass.getResource("/av007/manifest.txt")) { "missing av007/manifest.txt" }
            .readText()
            .lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }

    private fun records(kind: String): List<List<String>> = records.filter { it[0] == kind }.map { it.drop(1) }

    private fun operations(interfaceName: String): Set<String> {
        val type = Class.forName("org.ankivoice.core.contracts.$interfaceName")
        assertTrue(type.isInterface, "$interfaceName must be an interface")
        return type.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet()
    }

    @Test
    fun `the five contracts and their operations match the binding`() {
        val contracts = records("contract")
        assertEquals(contracts.map { it[0] }, Contract.entries.map { it.name })
        for (record in contracts) {
            assertEquals(record.drop(1).toSet(), operations(record[0]), record[0])
        }
    }

    @Test
    fun `the review transport seam matches the binding`() {
        val seams = records("seam")
        assertEquals(listOf("ReviewTransport"), seams.map { it[0] })
        for (record in seams) {
            assertEquals(record.drop(1).toSet(), operations(record[0]), record[0])
        }
    }

    @Test
    fun `failure names match per contract, in order`() {
        val failures = records("failure")
        assertEquals(34, failures.size)
        for (contract in Contract.entries) {
            assertEquals(
                failures.filter { it[0] == contract.name }.map { it[1] },
                failureModes(contract).map { it.specName },
                contract.name,
            )
        }
    }

    @Test
    fun `capability flags and their defaults match`() {
        val expected = records("capability").map { it[0] to it[1] }
        val names = Capabilities::class.primaryConstructor!!.parameters.map { it.name }
        assertEquals(expected.map { it.first }, names)
        val defaults = Capabilities()
        val properties = Capabilities::class.memberProperties.associateBy { it.name }
        for ((name, text) in expected) {
            assertEquals(text, manifestText(properties.getValue(name).get(defaults)), name)
        }
    }

    @Test
    fun `review states and their terminal flags match`() {
        assertEquals(
            records("reviewState").map { it[0] to (it[1] == "terminal") },
            ReviewState.entries.map { it.specName to it.isTerminal },
        )
    }

    @Test
    fun `grade labels and their proposals match`() {
        assertEquals(
            records("gradeLabel").map { it[0] to it[1].toIntOrNull() },
            GradeLabel.entries.map { it.specName to it.automaticProposal },
        )
    }

    /** The generator's spelling of a default: `1,2,3,4`, `60000`, `true`. */
    private fun manifestText(value: Any?): String = when (value) {
        is List<*> -> value.joinToString(",").ifEmpty { "none" }
        else -> value.toString()
    }
}
