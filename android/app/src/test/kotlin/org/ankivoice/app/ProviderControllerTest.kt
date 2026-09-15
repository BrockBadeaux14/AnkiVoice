package org.ankivoice.app

import org.ankivoice.provider.CredentialStore
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.LedgerStop
import org.ankivoice.provider.ProviderModule
import org.ankivoice.provider.QuotaLedger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executor

/** AV-020's settings surface: credentials, disclosure, allowance and diagnostics. */
class ProviderControllerTest {
    @TempDir
    lateinit var directory: File

    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
    private var now = 1_789_400_000_000L

    private class FakeSettings(
        override var dailyLimit: Int = QuotaLedger.DEFAULT_DAILY_LIMIT,
        override var disclosureAcknowledged: Boolean = false,
        override var retainContent: Boolean = false,
    ) : AppProviderSettings

    private class FakeCredentials(private var key: String? = null) : CredentialStore {
        override fun read(): String? = key
        override fun save(key: String): Boolean {
            if (!org.ankivoice.provider.CredentialPolicy.valid(key)) return false
            this.key = key.trim()
            return true
        }
        override fun clear() { key = null }
        override fun present(): Boolean = key != null
    }

    private val direct = Executor(Runnable::run)
    private val settings = FakeSettings()
    private val credentials = FakeCredentials()
    private val diagnostics = Diagnostics(clock = { now })
    private val ledger: QuotaLedger by lazy { QuotaLedger(File(directory, "ledger.jsonl")) { now } }

    private fun controller(): ProviderController = ProviderController(
        credentials,
        settings,
        ledger,
        diagnostics,
        ProviderModule.grading(credentials, ledger, settings, diagnostics),
        direct,
        direct,
    ).also { it.refresh() }

    @Test
    fun `grading is off until a key is saved and the disclosure is acknowledged`() {
        val controller = controller()
        assertFalse(controller.state.keyPresent)
        assertFalse(controller.state.gradingConfigured)

        controller.acknowledgeDisclosure()
        assertFalse(settings.disclosureAcknowledged, "there is nothing to acknowledge without a key")

        controller.saveKey(key)
        assertTrue(controller.state.keyPresent)
        assertFalse(controller.state.gradingConfigured, "the disclosure comes after the key")

        controller.acknowledgeDisclosure()
        assertTrue(controller.state.gradingConfigured)
    }

    @Test
    fun `replacing the key asks for the disclosure again`() {
        val controller = controller()
        controller.saveKey(key)
        controller.acknowledgeDisclosure()
        assertTrue(controller.state.gradingConfigured)

        controller.saveKey(key.dropLast(1) + "f")
        assertFalse(controller.state.disclosureAcknowledged)
        assertFalse(controller.state.gradingConfigured)
    }

    @Test
    fun `an invalid key is not stored and is never echoed`() {
        val controller = controller()
        controller.saveKey("not-a-key")
        assertFalse(controller.state.keyPresent)
        assertEquals("That is not an OpenRouter key. Nothing was saved.", controller.state.message)
        assertFalse(controller.state.message!!.contains("not-a-key"))
    }

    @Test
    fun `the key never appears in the state or the diagnostics`() {
        val controller = controller()
        controller.saveKey(key)
        controller.acknowledgeDisclosure()
        assertFalse(controller.state.toString().contains(key))
        assertFalse(controller.state.diagnostics.joinToString("\n").contains(key))
        assertTrue(controller.state.diagnostics.any { it.contains("credentialSaved") })
    }

    @Test
    fun `removing the key turns grading off and leaves self-grading available`() {
        val controller = controller()
        controller.saveKey(key)
        controller.acknowledgeDisclosure()
        controller.clearKey()
        assertFalse(controller.state.keyPresent)
        assertFalse(controller.state.disclosureAcknowledged)
        assertTrue(controller.state.message!!.contains("self-grading"))
    }

    @Test
    fun `the daily limit is configurable within its bounds`() {
        val controller = controller()
        controller.setDailyLimit(100)
        assertEquals(100, settings.dailyLimit)
        assertEquals(100, controller.state.dailyRemaining)

        for (rejected in listOf(-1, 1_001, 5_000)) {
            controller.setDailyLimit(rejected)
            assertEquals(100, settings.dailyLimit, "$rejected must be refused")
            assertEquals("Choose a daily limit between 0 and 1000.", controller.state.message)
        }
        controller.setDailyLimit(0)
        assertEquals(0, settings.dailyLimit)
    }

    @Test
    fun `the allowance shown is the durable one`() {
        val controller = controller()
        assertEquals(QuotaLedger.SESSION_LIMIT, controller.state.sessionRemaining)
        assertEquals(50, controller.state.dailyRemaining)

        ledger.stop(LedgerStop.RATE_LIMITED)
        controller.refresh()
        assertEquals(LedgerStop.RATE_LIMITED, controller.state.stop)
    }

    @Test
    fun `keeping study content is opt-in and clearable`() {
        val controller = controller()
        assertFalse(controller.state.retainContent)
        diagnostics.recordContent("transcript", "five blocks")
        assertEquals(emptyList<Diagnostics.Entry>(), diagnostics.retainedContent())

        controller.setRetainContent(true)
        assertTrue(settings.retainContent)
        diagnostics.recordContent("transcript", "five blocks")
        assertEquals(1, diagnostics.retainedContent().size)

        controller.setRetainContent(false)
        assertFalse(settings.retainContent)
        assertEquals(emptyList<Diagnostics.Entry>(), diagnostics.retainedContent())
    }

    @Test
    fun `diagnostics can be cleared and stay content-free`() {
        val controller = controller()
        controller.saveKey(key)
        assertTrue(controller.state.diagnostics.isNotEmpty())
        controller.clearDiagnostics()
        assertEquals(emptyList<String>(), controller.state.diagnostics)
        assertEquals("Diagnostics cleared.", controller.state.message)
    }

    @Test
    fun `a route check without a key reports unavailable grading and sends nothing`() {
        val controller = controller()
        controller.checkRoute()
        assertTrue(controller.state.message!!.contains("Add an OpenRouter key"), controller.state.message!!)
        assertFalse(controller.state.busy)
        assertNull(controller.state.stop)
        assertEquals(50, controller.state.dailyRemaining, "no allowance was spent")
    }
}
