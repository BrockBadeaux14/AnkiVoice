package org.ankivoice.provider

import java.math.BigDecimal
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

    private val cap = BigDecimal("1.00")
    private val hold = BigDecimal("0.0008192")

    private fun grant(ledger: QuotaLedger, count: Int, session: String = "s1", limit: Int = 50) =
        repeat(count) { assertInstanceOf(Reservation.Granted::class.java, ledger.reserve(session, limit)) }

    private fun same(expected: String, actual: BigDecimal) =
        assertTrue(BigDecimal(expected).compareTo(actual) == 0, "expected $expected, got ${actual.toPlainString()}")

    @Test
    fun `a reservation is on disk before the caller can dispatch`() {
        val ledger = ledger()
        assertFalse(file.exists())
        val granted = assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("s1", 50))
        assertEquals(1, granted.id)
        assertEquals(1, file.readLines().count { it.isNotBlank() })
        assertTrue(file.readText().contains("\"event\":\"reserve\""))
        assertTrue(file.readText().contains("\"route\":\"free\""))
    }

    @Test
    fun `reservations survive process death`() {
        grant(ledger(), 3)
        // A new instance is a new process: the file is the only record.
        val restarted = ledger()
        assertEquals(
            mapOf("reservedToday" to 3, "reservedThisSession" to 3, "reservedTotal" to 3, "paidReservedToday" to 0),
            restarted.counts("s1"),
        )
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
    fun `the request limits count both routes together`() {
        val ledger = ledger()
        grant(ledger, QuotaLedger.SESSION_LIMIT - 1, limit = 1_000)
        assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("s1", 1_000, cap, hold))
        assertEquals(Reservation.Refused(LedgerStop.SESSION_LIMIT), ledger.reserve("s1", 1_000))
        assertEquals(Reservation.Refused(LedgerStop.SESSION_LIMIT), ledger.reservePaid("s1", 1_000, cap, hold))
        // The daily limit too: one paid request on a limit of two leaves one free request.
        val daily = QuotaLedger(File(directory, "daily.jsonl")) { now }
        assertInstanceOf(Reservation.Granted::class.java, daily.reservePaid("s1", 2, cap, hold))
        assertInstanceOf(Reservation.Granted::class.java, daily.reserve("s1", 2))
        assertEquals(Reservation.Refused(LedgerStop.DAILY_LIMIT), daily.reserve("s1", 2))
        assertEquals(Reservation.Refused(LedgerStop.DAILY_LIMIT), daily.reservePaid("s1", 2, cap, hold))
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

    // AV-043: the paid route's spend

    @Test
    fun `a paid reservation holds the ceiling and the reply's cost replaces it`() {
        val ledger = ledger()
        val granted = assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("s1", 50, cap, hold))
        same("0.0008192", granted.holdUsd)
        same("0.0008192", ledger.budget(cap).spentTodayUsd)
        assertTrue(file.readText().contains("\"route\":\"paid\""))
        assertTrue(file.readText().contains("\"usd\":\"0.0008192\""))

        ledger.charge(granted.id, BigDecimal("0.0000318"))
        same("0.0000318", ledger.budget(cap).spentTodayUsd)
        same("0.9999682", ledger.budget(cap).remainingUsd)
        // The first evidence stands: a second charge for the same reservation is ignored.
        ledger.charge(granted.id, BigDecimal("0.5"))
        same("0.0000318", ledger.budget(cap).spentTodayUsd)
        assertEquals(1, ledger.counts("s1")["paidReservedToday"])
    }

    @Test
    fun `an uncharged paid reservation keeps its hold as spend`() {
        val ledger = ledger()
        repeat(2) { assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("s1", 50, cap, hold)) }
        same("0.0016384", ledger.budget(cap).spentTodayUsd)
        // A timeout or a crash mid-flight leaves exactly this record behind.
        same("0.0016384", ledger().budget(cap).spentTodayUsd)
    }

    @Test
    fun `a request that would pass the cap stops the paid route for the day and leaves the free route usable`() {
        val ledger = ledger()
        val small = BigDecimal("0.01")
        val first = assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("s1", 50, small, hold))
        ledger.charge(first.id, BigDecimal("0.0095"))
        // 0.0095 spent + a 0.0008192 hold would exceed 0.01: refused before dispatch, and stopped for the day.
        assertEquals(Reservation.Refused(LedgerStop.BUDGET_EXHAUSTED), ledger.reservePaid("s1", 50, small, hold))
        assertEquals(LedgerStop.BUDGET_EXHAUSTED, ledger.stopToday(GradingRoute.PAID))
        assertEquals(LedgerStop.BUDGET_EXHAUSTED, ledger.budget(small).stop)
        assertNull(ledger.stopToday(GradingRoute.FREE))
        assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("s1", 50))
        // Durable, like every stop.
        assertEquals(Reservation.Refused(LedgerStop.BUDGET_EXHAUSTED), ledger().reservePaid("s2", 50, small, hold))
    }

    @Test
    fun `a free-route stop leaves the paid route usable, and a paid stop the free route`() {
        val ledger = ledger()
        ledger.stop(LedgerStop.COST_NOT_VERIFIED)
        assertEquals(Reservation.Refused(LedgerStop.COST_NOT_VERIFIED), ledger.reserve("s1", 50))
        assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("s1", 50, cap, hold))

        ledger.stop(LedgerStop.RATE_LIMITED, GradingRoute.PAID)
        assertEquals(Reservation.Refused(LedgerStop.RATE_LIMITED), ledger.reservePaid("s1", 50, cap, hold))
        assertEquals(LedgerStop.COST_NOT_VERIFIED, ledger.stopToday(GradingRoute.FREE))
        // A stop for every route stops both.
        ledger.stop(LedgerStop.DAILY_LIMIT)
        assertEquals(LedgerStop.DAILY_LIMIT, ledger.stopToday(GradingRoute.FREE))
        assertEquals(LedgerStop.DAILY_LIMIT, ledger.stopToday(GradingRoute.PAID))
    }

    @Test
    fun `a zero cap refuses without recording a stop`() {
        val ledger = ledger()
        assertEquals(Reservation.Refused(LedgerStop.BUDGET_EXHAUSTED), ledger.reservePaid("s1", 50, BigDecimal.ZERO, hold))
        assertNull(ledger.stopToday(GradingRoute.PAID))
        assertFalse(ledger.budget(BigDecimal.ZERO).enabled)
        assertFalse(file.exists())
    }

    @Test
    fun `spend and paid stops survive process death and reset with the UTC day`() {
        val ledger = ledger()
        val granted = assertInstanceOf(Reservation.Granted::class.java, ledger.reservePaid("s1", 50, cap, hold))
        ledger.charge(granted.id, BigDecimal("0.25"))
        ledger.stop(LedgerStop.BUDGET_EXHAUSTED, GradingRoute.PAID)
        same("0.25", ledger().budget(cap).spentTodayUsd)
        assertEquals(LedgerStop.BUDGET_EXHAUSTED, ledger().budget(cap).stop)

        now += 24 * 60 * 60 * 1_000
        val tomorrow = ledger()
        same("0", tomorrow.budget(cap).spentTodayUsd)
        assertNull(tomorrow.budget(cap).stop)
        assertInstanceOf(Reservation.Granted::class.java, tomorrow.reservePaid("s2", 50, cap, hold))
    }

    @Test
    fun `a ledger written before AV-043 reads as free-route entries`() {
        val day = ledger().today()
        file.writeText(
            """{"event":"reserve","session":"old","day":"$day","id":1,"epoch":1,"stop":null}""" + "\n" +
                """{"event":"stop","session":"","day":"$day","id":2,"epoch":2,"stop":"COST_NOT_VERIFIED"}""" + "\n",
        )
        val legacy = ledger()
        assertEquals(1, legacy.counts("old")["reservedTotal"])
        assertEquals(0, legacy.counts("old")["paidReservedToday"])
        assertEquals(LedgerStop.COST_NOT_VERIFIED, legacy.stopToday(GradingRoute.FREE))
        assertNull(legacy.stopToday(GradingRoute.PAID), "only the free route existed when this stop was written")
        same("0", legacy.budget(cap).spentTodayUsd)
        // A request-limit stop written without a route applied to everything, and still does.
        file.appendText("""{"event":"stop","session":"","day":"$day","id":3,"epoch":3,"stop":"DAILY_LIMIT"}""" + "\n")
        assertEquals(LedgerStop.DAILY_LIMIT, ledger().stopToday(GradingRoute.PAID))
    }

    @Test
    fun `the daily cap is parsed as dollars and cents within its bounds`() {
        same("1.00", QuotaLedger.DEFAULT_DAILY_CAP_USD)
        same("10.00", QuotaLedger.MAX_DAILY_CAP_USD)
        assertEquals("1.00", QuotaLedger.parseDailyCap("1.00")?.toPlainString())
        assertEquals("0.50", QuotaLedger.parseDailyCap(" $0.5 ")?.toPlainString())
        assertEquals("2.00", QuotaLedger.parseDailyCap("2")?.toPlainString())
        assertEquals("0.00", QuotaLedger.parseDailyCap("0")?.toPlainString())
        assertEquals("10.00", QuotaLedger.parseDailyCap("10.00")?.toPlainString())
        for (rejected in listOf("10.01", "1.005", "-1", "abc", "", "1,00", "1e2")) {
            assertNull(QuotaLedger.parseDailyCap(rejected), rejected)
        }
        assertTrue(QuotaLedger.isValidDailyCap(BigDecimal.ZERO) && QuotaLedger.isValidDailyCap(BigDecimal("10")))
        assertFalse(QuotaLedger.isValidDailyCap(BigDecimal("-0.01")) || QuotaLedger.isValidDailyCap(BigDecimal("10.5")))
        assertEquals("1.00", QuotaLedger.formatUsd(BigDecimal("1")))
        assertEquals("0.0008192", QuotaLedger.formatUsd(BigDecimal("0.0008192"), 4))
        // A recorded amount is never rounded away: the display widens rather than hides a fraction of a cent.
        assertEquals("0.00318", QuotaLedger.formatUsd(BigDecimal("0.00318"), 4))
        assertEquals("0.0000318", QuotaLedger.formatUsd(BigDecimal("0.0000318"), 4))
        assertEquals("0.0000", QuotaLedger.formatUsd(BigDecimal.ZERO, 4))
    }
}
