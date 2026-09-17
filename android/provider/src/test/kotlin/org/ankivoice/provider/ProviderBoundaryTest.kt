package org.ankivoice.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/**
 * AV-043: no grading path reaches a writer. Both routes end in a `GradingReply`; nothing in
 * `:provider` takes or returns a review intent, a review writer or a review transport,
 * and no class in the module implements one. This is the structural form of the rule that
 * a label — free or paid — only ever proposes.
 */
class ProviderBoundaryTest {
    private val reviewTypes = setOf(
        "org.ankivoice.core.contracts.ReviewWriter",
        "org.ankivoice.core.contracts.ReviewTransport",
        "org.ankivoice.core.contracts.ReviewIntent",
        "org.ankivoice.core.contracts.GuardedReviewWriter",
        "org.ankivoice.core.contracts.ReviewOutcome",
        "org.ankivoice.core.journal.JournaledReviewWriter",
    )

    @Test
    fun `no provider class touches a review writer, transport, intent or outcome`() {
        val classes = providerClasses()
        assertTrue(classes.size > 8, "expected the compiled :provider classes, found ${classes.size}")
        val offenders = classes.flatMap { type ->
            val implemented = supertypes(type).filter { it.name in reviewTypes }.map { "${type.name} is a ${it.simpleName}" }
            val signatures = type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .filter { method -> touches(method) }
                .map { "${type.name}.${it.name}" }
            implemented + signatures
        }
        assertEquals(emptyList<String>(), offenders)
    }

    private fun touches(method: Method): Boolean =
        (method.parameterTypes + method.returnType).any { it.name in reviewTypes }

    private fun supertypes(type: Class<*>): List<Class<*>> =
        generateSequence<Class<*>>(type.superclass) { it.superclass }.toList() + type.interfaces.toList()

    private fun providerClasses(): List<Class<*>> {
        val location: Path = Paths.get(Diagnostics::class.java.protectionDomain.codeSource.location.toURI())
        val entries = if (Files.isDirectory(location)) {
            Files.walk(location).use { paths ->
                paths.filter { it.extension == "class" }.map { location.relativize(it).invariantSeparatorsPathString }.toList()
            }
        } else {
            JarFile(location.toFile()).use { jar -> jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList() }
        }
        return entries.mapNotNull { entry ->
            val name = entry.removeSuffix(".class").replace('/', '.')
            try {
                Class.forName(name, false, javaClass.classLoader)
            } catch (_: Throwable) {
                // A class that needs the Android framework is not loadable in a JVM test.
                null
            }
        }
    }
}
