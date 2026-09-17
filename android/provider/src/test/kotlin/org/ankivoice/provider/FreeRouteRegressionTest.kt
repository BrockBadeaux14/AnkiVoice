package org.ankivoice.provider

import org.ankivoice.core.contracts.GradeLabel
import org.ankivoice.core.contracts.GradingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * AV-043's regression test, driven by the recorded evidence that exposed the defect.
 *
 * AV-017's one live pass recorded the endpoints listing and the first grading reply; the
 * shipped guard refused that reply because it compared the reply's provider *name* with
 * the pinned endpoint *tag*. AV-006's grading evidence carried the same `Liquid` provider
 * on all 24 attempts. Both files are read exactly as committed; nothing here is invented.
 */
class FreeRouteRegressionTest {
    private val transcript = File(property("ankivoice.av043.av017Transcript"))
    private val gradeB = File(property("ankivoice.av043.av006GradeB"))
    private val gradeBPass2 = File(property("ankivoice.av043.av006GradeBPass2"))

    private fun property(name: String): String =
        checkNotNull(System.getProperty(name)) { "$name is not set; the provider test task declares it" }

    private fun lines(): List<Map<*, *>> = transcript.readLines().filter { it.isNotBlank() }
        .map { checkNotNull(Json.parse(it).asObject()) { "a transcript line is not an object" } }

    private fun recorded(kind: String): Any? {
        val line = lines().first { it.child("kind").asText() == kind }
        return Json.parse(checkNotNull(line.child("body").asText()))
    }

    private fun refusal(check: RouteCheck): String = assertInstanceOf(RouteCheck.Refused::class.java, check).reason

    private fun allowed(check: RouteCheck): RouteCheck.Allowed = assertInstanceOf(RouteCheck.Allowed::class.java, check)

    @Test
    fun `the recorded AV-017 listing names the pinned tag and the provider name`() {
        val check = allowed(FreeRoute.priceCheck(recorded(TranscriptLine.PRICE_CHECK)))
        assertTrue(check.endpointName.startsWith("Liquid | "), check.endpointName)
        assertEquals(setOf("liquid/fp8", "Liquid"), check.providers)
    }

    @Test
    fun `the recorded AV-017 reply the shipped guard refused is accepted with the listing's identities`() {
        val reply = recorded(TranscriptLine.COMPLETION)
        // The defect, reproduced from the evidence: the reply reports the provider by name.
        assertEquals("served by Liquid, not the pinned provider", refusal(FreeRoute.replyCheck(reply)))
        // The correction: the identities the listing supplied for this session are accepted.
        val accepted = allowed(FreeRoute.priceCheck(recorded(TranscriptLine.PRICE_CHECK))).providers
        assertEquals("Liquid", allowed(FreeRoute.replyCheck(reply, accepted)).endpointName)
        // And the reply that was refused was a well-formed two-key grade agreeing with the human label.
        val choice = reply.asObject()?.child("choices").asList()?.first().asObject()
        val result = GradingInstruction.validate(
            checkNotNull(choice?.child("message").asObject()?.child("content").asText()),
            choice?.child("finish_reason").asText(),
        )
        assertEquals(GradeLabel.CORRECT, assertInstanceOf(GradingResult::class.java, result).label)
    }

    @Test
    fun `every recorded AV-006 grading reply is accepted by the corrected check and refused by the old one`() {
        var checked = 0
        for (file in listOf(gradeB, gradeBPass2)) {
            val evidence = checkNotNull(Json.parse(file.readText()).asObject()) { "${file.name} is not an object" }
            val endpoint = checkNotNull(evidence.child("endpoint").asObject()) { "${file.name} records no endpoint" }
            val listing = mapOf("data" to mapOf("id" to evidence.child("model"), "endpoints" to listOf(endpoint)))
            val accepted = allowed(FreeRoute.priceCheck(listing)).providers
            assertEquals(setOf("liquid/fp8", "Liquid"), accepted, file.name)
            for (attempt in evidence.child("attempts").asList().orEmpty().mapNotNull { it.asObject() }) {
                val reply = mapOf(
                    "model" to attempt.child("served_model"),
                    "provider" to attempt.child("provider"),
                    "usage" to attempt.child("usage"),
                    "choices" to emptyList<Any?>(),
                )
                assertEquals("Liquid", attempt.child("provider"), "${file.name} ${attempt.child("case_id")}")
                assertEquals("served by Liquid, not the pinned provider", refusal(FreeRoute.replyCheck(reply)), attempt.child("case_id").toString())
                assertEquals("Liquid", allowed(FreeRoute.replyCheck(reply, accepted)).endpointName, attempt.child("case_id").toString())
                checked++
            }
        }
        assertEquals(24, checked, "AV-006 recorded 12 attempts per pass")
    }

    @Test
    fun `a foreign provider, a nonzero cost and an absent cost are still refused with the listing's identities`() {
        val accepted = allowed(FreeRoute.priceCheck(recorded(TranscriptLine.PRICE_CHECK))).providers
        val reply = checkNotNull(recorded(TranscriptLine.COMPLETION).asObject()).toMutableMap()
        val usage = checkNotNull(reply["usage"].asObject()).toMutableMap()
        assertTrue(refusal(FreeRoute.replyCheck(reply + ("provider" to "novita"), accepted)).contains("not the pinned provider"))
        assertTrue(refusal(FreeRoute.replyCheck(reply + ("model" to "liquid/lfm-2.5-2.6b"), accepted)).contains("not the pinned model"))
        assertEquals("reported cost is not zero", refusal(FreeRoute.replyCheck(reply + ("usage" to usage + ("cost" to "0.000001")), accepted)))
        assertEquals("no reported cost", refusal(FreeRoute.replyCheck(reply + ("usage" to (usage - "cost")), accepted)))
    }
}
