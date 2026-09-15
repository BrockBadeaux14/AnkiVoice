package org.ankivoice.core.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/** A transport or provider error is never an incorrect learner answer. */
class FailureTaxonomyTest {
    @Test
    fun `all 34 failures are grouped under their five contracts`() {
        assertEquals(34, ALL_FAILURE_MODES.size)
        assertEquals(
            mapOf(
                Contract.CardProvider to 9,
                Contract.SpeechOutput to 4,
                Contract.SpeechInput to 10,
                Contract.Grader to 5,
                Contract.ReviewWriter to 6,
            ),
            ALL_FAILURE_MODES.groupingBy { it.contract }.eachCount(),
        )
        for (contract in Contract.entries) {
            val names = failureModes(contract).map { it.specName }
            assertEquals(names.distinct(), names, contract.name)
        }
    }

    @Test
    fun `a failure mode is exhaustively matchable by contract`() {
        for (mode in ALL_FAILURE_MODES) {
            val contract = when (mode) {
                is CardProviderFailure -> Contract.CardProvider
                is SpeechOutputFailure -> Contract.SpeechOutput
                is SpeechInputFailure -> Contract.SpeechInput
                is GraderFailure -> Contract.Grader
                is ReviewWriterFailure -> Contract.ReviewWriter
            }
            assertEquals(contract, mode.contract, mode.specName)
        }
    }

    @Test
    fun `a shared name in two contracts is two distinct failures`() {
        val speech = Failure(SpeechInputFailure.QUOTA_EXHAUSTED)
        val grader = Failure(GraderFailure.QUOTA_EXHAUSTED)
        assertEquals(speech.mode.specName, grader.mode.specName)
        assertNotEquals(speech, grader)
        assertEquals(Contract.SpeechInput, speech.contract)
        assertEquals(Contract.Grader, grader.contract)
    }

    @Test
    fun `a failure reads as contract dot specification name`() {
        val cause = Failure(CardProviderFailure.ACCESS_DENIED, "revoked mid-turn")
        val failure = Failure(ReviewWriterFailure.PRECOMMIT_READ_FAILED, cause.toString(), cause)
        assertEquals("CardProvider.accessDenied: revoked mid-turn", cause.toString())
        assertEquals("Grader.quotaExhausted", Failure(GraderFailure.QUOTA_EXHAUSTED).toString())
        assertEquals(cause, failure.cause)
    }

    /**
     * Scans every compiled :core class. No public method may take a failure (or a result
     * that can hold one) and return an Int, and nothing declared on a failure type may
     * return an Int. Ratings are Ints, so this is the structural form of "a failure is
     * never convertible into a rating".
     */
    @Test
    fun `no api converts a failure into a rating`() {
        val failureTypes = listOf(Failure::class.java, FailureMode::class.java) +
            ALL_FAILURE_MODES.map { it.javaClass }.distinct()
        val failureCarriers = failureTypes + listOf(
            CapabilitiesResult::class.java,
            NextCardResult::class.java,
            ReadCardResult::class.java,
            GradingOutcome::class.java,
            CaptureEvent::class.java,
            PlaybackResult::class.java,
            RawAcknowledgement::class.java,
        )
        val ratingTypes = setOf(Int::class.javaPrimitiveType, Int::class.javaObjectType)
        val identityMethods = setOf("hashCode", "compareTo")

        val classes = coreClasses()
        assertTrue(classes.size > 20, "expected the compiled :core classes, found ${classes.size}")
        val offenders = classes.flatMap { type ->
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.returnType in ratingTypes }
                .filter { it.name !in identityMethods }
                .filter { method ->
                    failureTypes.any { it.isAssignableFrom(type) } ||
                        method.parameterTypes.any { parameter -> failureCarriers.any { it.isAssignableFrom(parameter) } }
                }
                .map { "${type.name}.${it.name}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** Every class compiled from src/main, whether the test sees them as a jar or a directory. */
    private fun coreClasses(): List<Class<*>> {
        val location: Path = Paths.get(Failure::class.java.protectionDomain.codeSource.location.toURI())
        val entries = if (Files.isDirectory(location)) {
            Files.walk(location).use { paths ->
                paths.filter { it.extension == "class" }.map { location.relativize(it).invariantSeparatorsPathString }.toList()
            }
        } else {
            JarFile(location.toFile()).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return entries
            .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && !it.endsWith("module-info.class") }
            .map { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, javaClass.classLoader) }
    }
}
