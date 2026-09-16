package org.ankivoice.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Build
import android.os.Looper
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.ankivoice.ankidroid.*
import org.ankivoice.core.contracts.*
import org.json.JSONObject
import org.json.JSONArray

/** Explicit, synthetic-only instrumentation. No test write is linked from the app UI. */
class ReviewInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle) { super.onCreate(arguments); args = arguments; start() }
    override fun onStart() {
        val output = JSONObject()
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
            check(args.getString("confirm") == "AV024_SYNTHETIC_ONLY")
            val platform = AndroidAccessPlatform(targetContext)
            val deckId = args.getString("deck")!!.toLong()
            val deck = if (platform.databasePermissionGranted() && platform.apiEnabled() != false)
                platform.queryDecks()?.singleOrNull { it.id == deckId } else null
            if (deck != null) check(deck.name.startsWith("AV002"))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val provider = AnkiDroidCardProvider(platform, deckId, worker, targetContext.mainExecutor)
                val mode = args.getString("mode") ?: "commit"
                val caps = provider.capabilities()
                output.put("capabilities", if (caps is Capabilities) JSONObject().put("maxReviewTimeMs", caps.maxReviewTimeMs)
                    .put("permittedRatings", JSONArray(caps.permittedRatings)) else caps.toString())
                when (mode) {
                    "read" -> output.put("read", describe(provider.readCard(args.getString("card")!!.toLong())))
                    "async" -> {
                        val latch = CountDownLatch(3)
                        val token = OperationToken("instrumented", 1, 1)
                        val results = mutableListOf<String>()
                        fun accept(actual: OperationToken, value: Any) {
                            check(Looper.myLooper() == Looper.getMainLooper())
                            check(actual == token)
                            results.add(value.javaClass.simpleName)
                            latch.countDown()
                        }
                        provider.capabilities(token) { accept(it.token, it.result) }
                        provider.nextCard(token) { accept(it.token, it.result) }
                        provider.readCard(token, args.getString("card")!!.toLong()) { accept(it.token, it.result) }
                        check(latch.await(30, TimeUnit.SECONDS))
                        output.put("asyncMainThreadReplies", JSONArray(results))
                    }
                    else -> {
                        if (mode == "stale") {
                            val first = provider.nextCard() as ScheduledCard
                            val prepToken = OperationToken("setup", 1, 1)
                            val prep = ReviewIntent(first, 4, 1000, prepToken)
                            prep.confirm(RatingConfirmation(prepToken, first.identity, 4, 0, ConfirmationSource.TOUCH))
                            val prepResult = GuardedReviewWriter(provider, AnkiDroidReviewTransport(platform), caps as Capabilities).commit(prep)
                            check(prepResult.state == ReviewState.CONFIRMED)
                            output.put("setupCard", first.identity.cardId)
                        }
                        val card = provider.nextCard()
                        output.put("next", describe(card))
                        if (mode in setOf("commit", "invalid", "stale")) {
                            check(caps is Capabilities && card is ScheduledCard)
                            val expectedCard = args.getString("card")!!.toLong()
                            check(mode == "stale" || card.identity.cardId == expectedCard) { "Wrong fixture scheduled" }
                            val token = OperationToken("instrumented", 1, 1)
                            val intent = ReviewIntent(card, args.getString("rating")!!.toInt(),
                                args.getString("elapsed")?.toLong() ?: 12345, token)
                            check(intent.confirm(RatingConfirmation(token, card.identity, intent.rating, 0, ConfirmationSource.TOUCH)))
                            var dispatches = 0
                            var dispatchDurationMs = 0L
                            val real = AnkiDroidReviewTransport(platform)
                            val counting = object : ReviewTransport {
                                override fun answerCard(identity: CardIdentity, rating: Int, elapsedMs: Long): RawAcknowledgement {
                                    dispatches++
                                    val began = System.nanoTime()
                                    try { return real.answerCard(identity, rating, elapsedMs) }
                                    finally { dispatchDurationMs = (System.nanoTime() - began + 999_999) / 1_000_000 }
                                }
                            }
                            if (mode == "stale") {
                                val resume = File(targetContext.filesDir, "av024-resume")
                                resume.delete()
                                File(targetContext.filesDir, "av024-held.json").writeText(describe(card).toString())
                                val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
                                while (!resume.exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                                check(resume.exists()) { "No host queue rebuild signal" }
                            }
                            val out = GuardedReviewWriter(provider, counting, caps).commit(intent)
                            output.put("outcome", JSONObject().put("state", out.state.specName).put("reason", out.reason)
                                .put("failure", out.failure?.toString()).put("acknowledgement", out.acknowledgement)
                                .put("preState", state(out.preState)).put("postState", state(out.postState))
                                .put("submittedTimeMs", out.submittedTimeMs).put("expectedStoredTimeMs", out.expectedStoredTimeMs)
                                .put("writeAttempted", out.writeAttempted).put("dispatches", dispatches)
                                .put("dispatchDurationMs", dispatchDurationMs))
                            if (mode == "invalid") check(dispatches == 0 && out.failure?.mode == ReviewWriterFailure.RATING_REJECTED)
                            else if (mode == "stale") check(dispatches == 0 && out.failure?.mode == ReviewWriterFailure.STALE_IDENTITY)
                            else check(dispatches == 1 && out.state in setOf(ReviewState.CONFIRMED, ReviewState.OUTCOME_UNKNOWN))
                            try { GuardedReviewWriter(provider, counting, caps).commit(intent); error("Replay accepted") }
                            catch (_: IllegalStateException) { check(dispatches <= 1) }
                        }
                    }
                }
            } finally { worker.shutdown() }
            output.put("passed", true)
        } catch (e: Throwable) { output.put("passed", false).put("error", e.toString()) }
        File(targetContext.filesDir, "av024-result.json").writeText(output.toString(2))
        finish(if (output.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("result", output.toString()) })
    }
    private fun describe(result: NextCardResult): Any = when (result) {
        is Failure -> result.toString()
        QueueExhausted -> "queueExhausted"
        is ScheduledCard -> JSONObject().put("cardId", result.identity.cardId).put("noteId", result.identity.noteId)
            .put("deckId", result.identity.deckId).put("ordinal", result.identity.ordinal).put("model", result.identity.model)
            .put("permittedRatings", JSONArray(result.permittedRatings)).put("state", state(result.state))
    }
    private fun state(value: CardState?): Any = value?.let {
        JSONObject().put("reps", it.reps).put("type", it.cardType).put("queue", it.queue)
            .put("due", it.due).put("interval", it.intervalDays).put("lastReviewTimeSecs", it.lastReviewTimeSecs)
    } ?: JSONObject.NULL
}
