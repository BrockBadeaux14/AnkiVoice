package org.ankivoice.provider

import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

/**
 * Why the ledger refused, or stopped. Each reads as plain settings text.
 *
 * A stop applies to one route or to both. The request limits apply to every route; the
 * guard and provider stops apply to the route they happened on, so a free-route refusal
 * leaves the paid fallback usable and a spent budget leaves the free route usable.
 */
enum class LedgerStop(val reason: String, private val legacyRoute: GradingRoute?) {
    SESSION_LIMIT("This session used its 30 grading requests.", null),
    DAILY_LIMIT("Today's grading limit is used up.", null),
    PAYMENT_REQUIRED("The provider asked for payment (HTTP 402), so grading on that route stopped for today.", GradingRoute.FREE),
    RATE_LIMITED("The provider rate-limited or exhausted the allowance (HTTP 429), so grading on that route stopped for today.", GradingRoute.FREE),
    COST_NOT_VERIFIED("A free-route reply did not report a verified zero cost, so free grading stopped for today.", GradingRoute.FREE),
    ROUTE_REFUSED("The pinned free route did not pass its zero-price check, so free grading stopped for today.", GradingRoute.FREE),
    PAID_ROUTE_REFUSED("The pinned paid route did not pass its price check, so paid grading stopped for today.", GradingRoute.PAID),
    BUDGET_EXHAUSTED("Today's paid grading budget is used up, so paid grading stopped for today. Self-grading stays available.", GradingRoute.PAID),
    ;

    /**
     * The route this stop applies to when the entry names one; otherwise the route the
     * stop meant before AV-043, when only the free route existed. Null is every route.
     */
    internal fun routeOf(recorded: GradingRoute?): GradingRoute? = recorded ?: legacyRoute

    /** The default scope for a stop written without an explicit route. */
    internal val defaultRoute: GradingRoute? get() = legacyRoute
}

sealed interface Reservation {
    /**
     * Already durable when it is returned: a crash after this still consumes the allowance.
     * [holdUsd] is the paid ceiling held against the cap until the reply's cost replaces it;
     * zero for the free route.
     */
    data class Granted(val id: Long, val holdUsd: BigDecimal = BigDecimal.ZERO) : Reservation

    data class Refused(val stop: LedgerStop) : Reservation
}

data class Allowance(
    val sessionRemaining: Int,
    val dailyRemaining: Int,
    val stop: LedgerStop?,
) {
    val grantable: Boolean get() = stop == null && sessionRemaining > 0 && dailyRemaining > 0
}

/** The paid route's day: the owner's cap, what is spent or held so far, and any paid stop. */
data class Budget(
    val capUsd: BigDecimal,
    val spentTodayUsd: BigDecimal,
    val stop: LedgerStop?,
) {
    /** A cap of zero turns the paid route off. */
    val enabled: Boolean get() = capUsd.signum() > 0

    val remainingUsd: BigDecimal get() = (capUsd - spentTodayUsd).max(BigDecimal.ZERO)

    /** Whether a request that can cost at most [holdUsd] still fits under the cap today. */
    fun fits(holdUsd: BigDecimal): Boolean = enabled && stop == null && spentTodayUsd + holdUsd <= capUsd
}

/**
 * A durable, append-only grading allowance.
 *
 * Every request is reserved on disk and flushed to the filesystem *before* it is
 * dispatched, so a timeout, a crash or process death still consumes the allowance. The
 * file is the record: a new instance over the same file sees the same counts.
 *
 * Limits are AV-006's: at most [SESSION_LIMIT] requests per session, and a configurable
 * daily limit, default [DEFAULT_DAILY_LIMIT] per UTC day, settable only between 0 and
 * [MAX_DAILY_LIMIT]. Both count free and paid requests together. Retries are the caller's
 * explicit, one-per-learner-action choice and are only granted within the remaining
 * allowance; nothing here retries anything.
 *
 * AV-043 adds the paid route's spend. A paid reservation holds the request's ceiling
 * against the owner's daily USD cap until [charge] records the reply's reported cost; an
 * uncharged reservation — a timeout, a crash, an unreadable reply — keeps its hold as spend
 * for the day, because the money may have been spent. When a request's ceiling would take
 * the day past the cap, the paid route stops for the UTC day with [LedgerStop.BUDGET_EXHAUSTED],
 * exactly as a request-quota stop does. The cap is a ceiling, not a target.
 *
 * The app never adds funds or raises a cap on its own. Deleting app data removes this
 * file, which is not an allowance or budget increase; the settings text says so.
 */
class QuotaLedger(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val SESSION_LIMIT: Int = 30
        const val DEFAULT_DAILY_LIMIT: Int = 50
        const val MAX_DAILY_LIMIT: Int = 1_000

        /** AV-043's default daily cap on paid grading, in USD. Zero disables the paid route. */
        val DEFAULT_DAILY_CAP_USD: BigDecimal = BigDecimal("1.00")

        /** The most the settings screen accepts per UTC day. */
        val MAX_DAILY_CAP_USD: BigDecimal = BigDecimal("10.00")

        /** The configurable daily limit, clamped to the range the user decided. */
        fun validDailyLimit(limit: Int): Int = limit.coerceIn(0, MAX_DAILY_LIMIT)

        fun isValidDailyLimit(limit: Int): Boolean = limit in 0..MAX_DAILY_LIMIT

        /** Whole dollars and cents, from zero to [MAX_DAILY_CAP_USD]. */
        fun isValidDailyCap(cap: BigDecimal): Boolean =
            cap.signum() >= 0 && cap <= MAX_DAILY_CAP_USD && cap.stripTrailingZeros().scale() <= 2

        /**
         * A cap typed on the settings screen: an optional `$`, dollars, at most two decimals.
         * Null for anything else, so a typo never becomes a budget.
         */
        fun parseDailyCap(text: String): BigDecimal? {
            val cleaned = text.trim().removePrefix("$").trim()
            if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it == '.' }) return null
            val cap = try {
                BigDecimal(cleaned)
            } catch (_: NumberFormatException) {
                return null
            }
            return cap.takeIf(::isValidDailyCap)?.setScale(2, RoundingMode.UNNECESSARY)
        }

        /** The cap as stored and shown: two decimals, no exponent. */
        fun formatUsd(amount: BigDecimal, scale: Int = 2): String =
            amount.setScale(maxOf(scale, amount.stripTrailingZeros().scale().coerceAtLeast(0)), RoundingMode.HALF_UP).toPlainString()
    }

    private class Entry(
        val event: String,
        val session: String,
        val day: String,
        val id: Long,
        val stop: LedgerStop?,
        val route: GradingRoute?,
        val usd: BigDecimal?,
        val reservation: Long?,
    )

    private val entries = ArrayList<Entry>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                if (line.isNotBlank()) parse(line)?.let(entries::add)
            }
        }
    }

    @Synchronized
    fun today(): String = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /**
     * Checked before a session starts, and again before every request. The session and
     * daily counts include both routes; the stop is the one that applies to [route].
     */
    @Synchronized
    fun allowance(sessionId: String, dailyLimit: Int, route: GradingRoute = GradingRoute.FREE): Allowance {
        val limit = validDailyLimit(dailyLimit)
        val day = today()
        val reservations = entries.filter { it.event == RESERVE }
        val session = SESSION_LIMIT - reservations.count { it.session == sessionId }
        val daily = limit - reservations.count { it.day == day }
        val stop = stopToday(route) ?: when {
            session <= 0 -> LedgerStop.SESSION_LIMIT
            daily <= 0 -> LedgerStop.DAILY_LIMIT
            else -> null
        }
        return Allowance(session.coerceAtLeast(0), daily.coerceAtLeast(0), stop)
    }

    /** Persist before dispatch. The returned grant is already on disk. */
    @Synchronized
    fun reserve(sessionId: String, dailyLimit: Int, route: GradingRoute = GradingRoute.FREE): Reservation {
        val allowance = allowance(sessionId, dailyLimit, route)
        allowance.stop?.let { return Reservation.Refused(it) }
        val id = nextId()
        append(Entry(RESERVE, sessionId, today(), id, null, route, null, null))
        return Reservation.Granted(id)
    }

    /**
     * A paid reservation: the request limits, then the cap. [holdUsd] is the request's
     * ceiling, held as spend until [charge] replaces it. When it would take the day past
     * [capUsd], the paid route stops for the UTC day and nothing is reserved. A cap of zero
     * refuses without recording a stop: the route is off, not exhausted.
     */
    @Synchronized
    fun reservePaid(sessionId: String, dailyLimit: Int, capUsd: BigDecimal, holdUsd: BigDecimal): Reservation {
        require(holdUsd.signum() >= 0) { "a hold is not negative" }
        val allowance = allowance(sessionId, dailyLimit, GradingRoute.PAID)
        allowance.stop?.let { return Reservation.Refused(it) }
        val budget = budget(capUsd)
        if (!budget.enabled) return Reservation.Refused(LedgerStop.BUDGET_EXHAUSTED)
        if (!budget.fits(holdUsd)) {
            stop(LedgerStop.BUDGET_EXHAUSTED, GradingRoute.PAID)
            return Reservation.Refused(LedgerStop.BUDGET_EXHAUSTED)
        }
        val id = nextId()
        append(Entry(RESERVE, sessionId, today(), id, null, GradingRoute.PAID, holdUsd, null))
        return Reservation.Granted(id, holdUsd)
    }

    /**
     * The cost a paid reply reported for [reservationId], or the ceiling when it reported
     * none. It replaces the reservation's hold; a second charge for the same reservation is
     * ignored, so the first evidence stands.
     */
    @Synchronized
    fun charge(reservationId: Long, usd: BigDecimal) {
        require(usd.signum() >= 0) { "a charge is not negative" }
        if (entries.any { it.event == CHARGE && it.reservation == reservationId }) return
        val reservation = entries.firstOrNull { it.event == RESERVE && it.id == reservationId } ?: return
        append(Entry(CHARGE, reservation.session, today(), nextId(), null, GradingRoute.PAID, usd, reservationId))
    }

    /**
     * A durable stop for the rest of the UTC day, for [route] or for every route when it
     * is null. The pre-session price checks are not grading requests and never reserve,
     * but they can stop their route.
     */
    @Synchronized
    fun stop(stop: LedgerStop, route: GradingRoute? = stop.defaultRoute) {
        append(Entry(STOP, "", today(), nextId(), stop, route, null, null))
    }

    /** A sticky stop recorded earlier today that applies to [route], or null. */
    @Synchronized
    fun stopToday(route: GradingRoute = GradingRoute.FREE): LedgerStop? {
        val day = today()
        return entries.lastOrNull { it.event == STOP && it.day == day && it.stop != null && it.stop.routeOf(it.route).let { scope -> scope == null || scope == route } }?.stop
    }

    /**
     * Today's paid spend against [capUsd]: every charge recorded today, plus the hold of
     * every paid reservation made today that has no charge yet.
     */
    @Synchronized
    fun budget(capUsd: BigDecimal): Budget {
        val day = today()
        val charged = entries.filter { it.event == CHARGE }.mapNotNull { it.reservation }.toSet()
        val charges = entries.filter { it.event == CHARGE && it.day == day }.sumOf { it.usd ?: BigDecimal.ZERO }
        val holds = entries.filter { it.event == RESERVE && it.day == day && it.route == GradingRoute.PAID && it.id !in charged }
            .sumOf { it.usd ?: BigDecimal.ZERO }
        return Budget(capUsd.max(BigDecimal.ZERO), charges + holds, stopToday(GradingRoute.PAID))
    }

    /** Reserved requests today and in total; used by the content-free diagnostics. */
    @Synchronized
    fun counts(sessionId: String): Map<String, Int> {
        val day = today()
        val reservations = entries.filter { it.event == RESERVE }
        return linkedMapOf(
            "reservedToday" to reservations.count { it.day == day },
            "reservedThisSession" to reservations.count { it.session == sessionId },
            "reservedTotal" to reservations.size,
            "paidReservedToday" to reservations.count { it.day == day && it.route == GradingRoute.PAID },
        )
    }

    private fun nextId(): Long = (entries.maxOfOrNull { it.id } ?: 0) + 1

    private fun append(entry: Entry) {
        val record = linkedMapOf<String, Any?>(
            "event" to entry.event,
            "session" to entry.session,
            "day" to entry.day,
            "id" to entry.id,
            "epoch" to clock(),
            "stop" to entry.stop?.name,
        )
        entry.route?.let { record["route"] = it.specName }
        entry.usd?.let { record["usd"] = it.toPlainString() }
        entry.reservation?.let { record["reservation"] = it }
        val line = Json.write(record) + "\n"
        file.parentFile?.mkdirs()
        // Flush through to the filesystem: the reservation must outlive process death.
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        entries.add(entry)
    }

    private fun parse(line: String): Entry? {
        val row = try {
            Json.parse(line).asObject()
        } catch (_: RuntimeException) {
            // A torn final line from a kill mid-write is ignored; the rest of the file stands.
            return null
        } ?: return null
        val event = row.child("event").asText() ?: return null
        val route = row.child("route").asText()?.let { name -> GradingRoute.entries.firstOrNull { it.specName == name } }
        return Entry(
            event = event,
            session = row.child("session").asText().orEmpty(),
            day = row.child("day").asText().orEmpty(),
            id = JsonText.number(row.child("id"))?.toDoubleOrNull()?.toLong() ?: 0,
            stop = row.child("stop").asText()?.let { name -> LedgerStop.entries.firstOrNull { it.name == name } },
            // A reservation written before AV-043 named no route; only the free route existed.
            route = route ?: if (event == RESERVE) GradingRoute.FREE else null,
            usd = JsonText.number(row.child("usd"))?.let { text ->
                try {
                    BigDecimal(text.trim())
                } catch (_: NumberFormatException) {
                    null
                }
            },
            reservation = JsonText.number(row.child("reservation"))?.toDoubleOrNull()?.toLong(),
        )
    }
}

private const val RESERVE = "reserve"
private const val CHARGE = "charge"
private const val STOP = "stop"
