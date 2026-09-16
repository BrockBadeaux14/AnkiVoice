package org.ankivoice.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The allowance is durable: it is written before dispatch and survives process death. */
class QuotaLedgerTest {
    @TempDir
    lateinit var directory: File

    private var now = 1_789_400_000_000L // 2026-09-15T…Z
    private val file: File get() = File(directory, "ledger.jsonl")
    private fun ledger() = QuotaLedger(file) { now }

    private fun grant(ledger: QuotaLedger, count: Int, session: String = "s1", limit: Int = 50) =
        repeat(count) { assertInstanceOf(Reservation.Granted::class.java, ledger.reserve(session, limit)) }

    @Test
    fun `a reservation is on disk before the caller can dispatch`() {
        val ledger = ledger()
        assertFalse(file.exists())
        val granted = assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("s1", 50))
        assertEquals(1, granted.id)
        assertEquals(1, file.readLines().count { it.isNotBlank() })
        assertTrue(file.readText().contains("\"event\":\"reserve\""))
    }

    @Test
    fun `reservations survive process death`() {
        grant(ledger(), 3)
        // A new instance is a new process: the file is the only record.
        val restarted = ledger()
        assertEquals(mapOf("reservedToday" to 3, "reservedThisSession" to 3, "reservedTotal" to 3), restarted.counts("s1"))
        assertEquals(27, restarted.allowance("s1", 50).sessionRemaining)
        assertEquals(47, restarted.allowance("s1", 50).dailyRemaining)
    }

    @Test
    fun `a session stops at thirty requests`() {
        val ledger = ledger()
        grant(ledger, QuotaLedger.SESSION_LIMIT, limit = 1_000)
        assertEquals(Reservation.Refused(LedgerStop.SESSION_LIMIT), ledger.reserve("s1", 1_000))
        assertFalse(ledger.allowance("s1", 1_000).grantable)
        // A different session still has its own allowance within the daily limit.
        assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("s2", 1_000))
    }

    @Test
    fun `the daily limit defaults to fifty and is configurable`() {
        assertEquals(50, QuotaLedger.DEFAULT_DAILY_LIMIT)
        val ledger = ledger()
        grant(ledger, 5, session = "s1", limit = 5)
        assertEquals(Reservation.Refused(LedgerStop.DAILY_LIMIT), ledger.reserve("s2", 5))
        // Raising the configured limit is the learner's setting, within its bounds.
        assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("s2", 6))
    }

    @Test
    fun `the configurable daily limit is bounded to zero through a thousand`() {
        assertTrue(QuotaLedger.isValidDailyLimit(0) && QuotaLedger.isValidDailyLimit(1_000))
        assertFalse(QuotaLedger.isValidDailyLimit(-1) || QuotaLedger.isValidDailyLimit(1_001))
        assertEquals(0, QuotaLedger.validDailyLimit(-5))
        assertEquals(1_000, QuotaLedger.validDailyLimit(5_000))
        assertEquals(Reservation.Refused(LedgerStop.DAILY_LIMIT), ledger().reserve("s1", 0))
    }

    @Test
    fun `a payment or rate-limit stop is durable for the rest of the day`() {
        val ledger = ledger()
        grant(ledger, 1)
        ledger.stop(LedgerStop.RATE_LIMITED)
        assertEquals(LedgerStop.RATE_LIMITED, ledger.stopToday())
        assertEquals(Reservation.Refused(LedgerStop.RATE_LIMITED), ledger.reserve("s1", 50))
        // Still stopped after a restart, and for a new session.
        assertEquals(Reservation.Refused(LedgerStop.RATE_LIMITED), ledger().reserve("s2", 50))
    }

    @Test
    fun `a new UTC day restores the daily allowance and clears the stop`() {
        val ledger = ledger()
        grant(ledger, 2)
        ledger.stop(LedgerStop.PAYMENT_REQUIRED)
        val day = ledger.today()
        now += 24 * 60 * 60 * 1_000
        val tomorrow = ledger()
        assertFalse(tomorrow.today() == day)
        assertNull(tomorrow.stopToday())
        assertEquals(50, tomorrow.allowance("s2", 50).dailyRemaining)
        // The session limit is per session, not per day: an old session stays spent.
        assertEquals(28, tomorrow.allowance("s1", 50).sessionRemaining)
    }

    @Test
    fun `a torn final line does not lose the rest of the ledger`() {
        grant(ledger(), 2)
        file.appendText("{\"event\":\"rese")
        assertEquals(2, ledger().counts("s1")["reservedTotal"])
    }
}
