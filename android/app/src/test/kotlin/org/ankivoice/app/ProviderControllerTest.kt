package org.ankivoice.app

import java.math.BigDecimal
import org.ankivoice.provider.CredentialStore
import org.ankivoice.provider.Diagnostics
import org.ankivoice.provider.GradingRoute
import org.ankivoice.provider.LedgerStop
import org.ankivoice.provider.ProviderModule
import org.ankivoice.provider.QuotaLedger
import org.ankivoice.provider.Reservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executor

/** AV-020's settings surface: credentials, disclosure, allowance and diagnostics; AV-043's cap and spend. */
class ProviderControllerTest {
    @TempDir
    lateinit var directory: File

    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
    private var now = 1_789_400_000_000L

    private class FakeSettings(
        override var dailyLimit: Int = QuotaLedger.DEFAULT_DAILY_LIMIT,
        override var disclosureAcknowledged: Boolean = false,
        override var dailyCapUsd: BigDecimal = QuotaLedger.DEFAULT_DAILY_CAP_USD,
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

    private fun same(expected: String, actual: BigDecimal) =
        assertTrue(BigDecimal(expected).compareTo(actual) == 0, "expected $expected, got ${actual.toPlainString()}")

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
        // The paid smoke request needs the same key, and spends nothing without one.
        controller.sendSmokeRequest(GradingRoute.PAID)
        assertTrue(controller.state.message!!.contains("Add an OpenRouter key"), controller.state.message!!)
        same("0", controller.state.spentTodayUsd)
    }

    // AV-043: the paid route's cap and spend

    @Test
    fun `the paid budget defaults to one dollar and is shown beside the request counters`() {
        val controller = controller()
        same("1.00", controller.state.dailyCapUsd)
        same("0", controller.state.spentTodayUsd)
        assertTrue(controller.state.paidEnabled)
        assertNull(controller.state.paidStop)
    }

    @Test
    fun `the daily cap is configurable in dollars and cents, and zero turns the paid route off`() {
        val controller = controller()
        controller.setDailyCap("0.50")
        same("0.50", settings.dailyCapUsd)
        same("0.50", controller.state.dailyCapUsd)
        assertTrue(controller.state.message!!.contains("\$0.50"), controller.state.message!!)
        assertTrue(controller.state.message!!.contains("ceiling, not a target"))

        for (rejected in listOf("abc", "-1", "10.01", "1,00", "")) {
            controller.setDailyCap(rejected)
            same("0.50", settings.dailyCapUsd)
            assertEquals("Choose a daily paid budget from \$0 to \$10.00, in dollars and cents.", controller.state.message, rejected)
        }

        controller.setDailyCap("0")
        same("0", settings.dailyCapUsd)
        assertFalse(controller.state.paidEnabled)
        assertTrue(controller.state.message!!.startsWith("Paid route off"), controller.state.message!!)
        assertTrue(controller.state.diagnostics.any { it.contains("dailyCapSet") })
    }

    @Test
    fun `the spend shown is the durable one, and a paid stop is shown apart from the free one`() {
        val controller = controller()
        val granted = assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("earlier", 50, BigDecimal("1.00"), BigDecimal("0.0008192")))
        controller.refresh()
        same("0.0008192", controller.state.spentTodayUsd)
        ledger.charge(granted.id, BigDecimal("0.0000318"))
        controller.refresh()
        same("0.0000318", controller.state.spentTodayUsd)

        ledger.stop(LedgerStop.BUDGET_EXHAUSTED, GradingRoute.PAID)
        controller.refresh()
        assertEquals(LedgerStop.BUDGET_EXHAUSTED, controller.state.paidStop)
        assertNull(controller.state.stop, "the free route is not stopped by a spent budget")
        assertEquals(49, controller.state.dailyRemaining, "the paid reservation counts against the request limit too")

        // A stop for every route is shown once, beside the request counters.
        ledger.stop(LedgerStop.DAILY_LIMIT)
        controller.refresh()
        assertEquals(LedgerStop.DAILY_LIMIT, controller.state.stop)
        assertNull(controller.state.paidStop)
    }

    @Test
    fun `the disclosure names the cost, the cap and that a stop never rates a card`() {
        val text = DISCLOSURE_COST.joinToString("\n")
        assertTrue(text.contains(ProviderModule.freeRouteDescription))
        assertTrue(text.contains(ProviderModule.paidRouteDescription))
        assertTrue(text.contains("\$1.00"))
        assertTrue(text.contains("\$0 turns the paid route off"))
        assertTrue(text.contains("never rates a card"))
        assertTrue(ProviderModule.routeDescription.contains("first"))
    }
}
