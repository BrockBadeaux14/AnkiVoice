package org.ankivoice.provider

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset

/** Why the ledger refused, or stopped. Each reads as plain settings text. */
enum class LedgerStop(val reason: String) {
    SESSION_LIMIT("This session used its 30 grading requests."),
    DAILY_LIMIT("Today's grading limit is used up."),
    PAYMENT_REQUIRED("The provider asked for payment (HTTP 402), so grading stopped for today."),
    RATE_LIMITED("The provider rate-limited or exhausted the free allowance (HTTP 429), so grading stopped for today."),
    COST_NOT_VERIFIED("A reply did not report a verified zero cost, so grading stopped for today."),
    ROUTE_REFUSED("The pinned free route did not pass its zero-price check, so grading stopped for today."),
}

sealed interface Reservation {
    /** Already durable when it is returned: a crash after this still consumes the allowance. */
    data class Granted(val id: Long) : Reservation

    data class Refused(val stop: LedgerStop) : Reservation
}

data class Allowance(
    val sessionRemaining: Int,
    val dailyRemaining: Int,
    val stop: LedgerStop?,
) {
    val grantable: Boolean get() = stop == null && sessionRemaining > 0 && dailyRemaining > 0
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
 * [MAX_DAILY_LIMIT]. Retries are the caller's explicit, one-per-learner-action choice and
 * are only granted within the remaining allowance; nothing here retries anything.
 *
 * The app never adds funds, raises a cap, or switches to a paid model or provider.
 * Deleting app data removes this file, which is not an allowance increase; the settings
 * text says so.
 */
class QuotaLedger(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val SESSION_LIMIT: Int = 30
        const val DEFAULT_DAILY_LIMIT: Int = 50
        const val MAX_DAILY_LIMIT: Int = 1_000

        /** The configurable daily limit, clamped to the range the user decided. */
        fun validDailyLimit(limit: Int): Int = limit.coerceIn(0, MAX_DAILY_LIMIT)

        fun isValidDailyLimit(limit: Int): Boolean = limit in 0..MAX_DAILY_LIMIT
    }

    private class Entry(val event: String, val session: String, val day: String, val id: Long, val stop: LedgerStop?)

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

    /** Checked before a session starts, and again before every request. */
    @Synchronized
    fun allowance(sessionId: String, dailyLimit: Int): Allowance {
        val limit = validDailyLimit(dailyLimit)
        val day = today()
        val reservations = entries.filter { it.event == RESERVE }
        val session = SESSION_LIMIT - reservations.count { it.session == sessionId }
        val daily = limit - reservations.count { it.day == day }
        val stop = stopToday() ?: when {
            session <= 0 -> LedgerStop.SESSION_LIMIT
            daily <= 0 -> LedgerStop.DAILY_LIMIT
            else -> null
        }
        return Allowance(session.coerceAtLeast(0), daily.coerceAtLeast(0), stop)
    }

    /** Persist before dispatch. The returned grant is already on disk. */
    @Synchronized
    fun reserve(sessionId: String, dailyLimit: Int): Reservation {
        val allowance = allowance(sessionId, dailyLimit)
        allowance.stop?.let { return Reservation.Refused(it) }
        val id = (entries.maxOfOrNull { it.id } ?: 0) + 1
        append(Entry(RESERVE, sessionId, today(), id, null))
        return Reservation.Granted(id)
    }

    /**
     * A durable stop for the rest of the UTC day. The pre-session price check is not a
     * grading request and never reserves, but it can stop the ledger.
     */
    @Synchronized
    fun stop(stop: LedgerStop) {
        append(Entry(STOP, "", today(), (entries.maxOfOrNull { it.id } ?: 0) + 1, stop))
    }

    /** A sticky stop recorded earlier today, or null. */
    @Synchronized
    fun stopToday(): LedgerStop? {
        val day = today()
        return entries.lastOrNull { it.event == STOP && it.day == day }?.stop
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
        )
    }

    private fun append(entry: Entry) {
        val line = Json.write(
            linkedMapOf(
                "event" to entry.event,
                "session" to entry.session,
                "day" to entry.day,
                "id" to entry.id,
                "epoch" to clock(),
                "stop" to entry.stop?.name,
            ),
        ) + "\n"
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
        return Entry(
            event = event,
            session = row.child("session").asText().orEmpty(),
            day = row.child("day").asText().orEmpty(),
            id = JsonText.number(row.child("id"))?.toDoubleOrNull()?.toLong() ?: 0,
            stop = row.child("stop").asText()?.let { name -> LedgerStop.entries.firstOrNull { it.name == name } },
        )
    }
}

private const val RESERVE = "reserve"
private const val STOP = "stop"
