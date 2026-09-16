package org.ankivoice.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/** Content-free diagnostics, log redaction and the credential's shape. */
class DiagnosticsAndCredentialsTest {
    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"

    @Test
    fun `a recorded detail can never carry the key`() {
        val diagnostics = Diagnostics()
        diagnostics.record("providerError", 12, "upstream said Bearer $key is invalid")
        val entry = diagnostics.entries().single()
        assertFalse(entry.detail.contains(key), entry.detail)
        assertTrue(entry.detail.contains("sk-or-…redacted"))
        assertEquals(12, entry.durationMs)
    }

    @Test
    fun `study content is dropped unless the learner opts in`() {
        val diagnostics = Diagnostics()
        diagnostics.record("reserved")
        diagnostics.recordContent("transcript", "the learner said five blocks")
        assertEquals(emptyList<Diagnostics.Entry>(), diagnostics.retainedContent())

        diagnostics.retainContent = true
        diagnostics.recordContent("transcript", "the learner said five blocks")
        assertEquals("the learner said five blocks", diagnostics.retainedContent().single().detail)

        // Clearing study content leaves the content-free record in place.
        diagnostics.clearRetainedContent()
        assertEquals(emptyList<Diagnostics.Entry>(), diagnostics.retainedContent())
        assertEquals(listOf("reserved"), diagnostics.entries().map { it.name })
    }

    @Test
    fun `the record is bounded and clearable`() {
        val diagnostics = Diagnostics(capacity = 3)
        repeat(5) { diagnostics.record("request$it") }
        assertEquals(listOf("request2", "request3", "request4"), diagnostics.entries().map { it.name })
        diagnostics.clear()
        assertEquals(emptyList<Diagnostics.Entry>(), diagnostics.entries())
    }

    @Test
    fun `only an OpenRouter key is accepted`() {
        assertTrue(CredentialPolicy.valid(key))
        assertTrue(CredentialPolicy.valid("  $key  "), "surrounding space is trimmed")
        assertFalse(CredentialPolicy.valid(""))
        assertFalse(CredentialPolicy.valid("sk-or-short"))
        assertFalse(CredentialPolicy.valid("sk-ant-0123456789abcdef0123456789abcdef"))
        assertFalse(CredentialPolicy.valid("sk-or-v1-0123456789 abcdef0123456789abcdef"))
    }

    @Test
    fun `a store keeps no invalid value and can be cleared`() {
        val store = InMemoryCredentialStore()
        assertFalse(store.present())
        assertFalse(store.save("nope"))
        assertNull(store.read())
        assertTrue(store.save(" $key "))
        assertEquals(key, store.read())
        assertEquals("A key is saved on this device.", CredentialPolicy.describe(store.present()))
        store.clear()
        assertNull(store.read())
        assertEquals("No key is saved.", CredentialPolicy.describe(store.present()))
    }

    /**
     * Raw audio is never written to disk because no `:provider` API accepts any: speech
     * is native and belongs to #26. This is the structural form of that rule.
     */
    @Test
    fun `no provider api accepts audio bytes or a stream`() {
        val audioTypes = setOf(ByteArray::class.java, InputStream::class.java)
        val classes = providerClasses()
        assertTrue(classes.size > 8, "expected the compiled :provider classes, found ${classes.size}")
        val offenders = classes.flatMap { type ->
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .filter { method -> method.parameterTypes.any { parameter -> audioTypes.any { it.isAssignableFrom(parameter) } } }
                .map { "${type.name}.${it.name}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

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
