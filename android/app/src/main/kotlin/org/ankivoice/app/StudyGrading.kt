package org.ankivoice.app

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.ankivoice.core.contracts.Grader
import org.ankivoice.core.contracts.GradingReply
import org.ankivoice.core.contracts.GradingRequest
import org.ankivoice.core.grading.RuleGrader
import org.ankivoice.provider.GradingProvider
import org.ankivoice.provider.SemanticGrader

/**
 * AV-045: the grader behind the study session — #16's rules on device, then #18's
 * [SemanticGrader] over #17's route, and only on a rule miss.
 *
 * The rules are checked here before anything touches the provider, so a rule match opens
 * no connection at all: not a grading request, and not the pre-session price check either.
 * The first rule miss runs [GradingProvider.startSession] once, which applies #17's key,
 * disclosure, allowance and zero-price checks before the first reservation; if any of them
 * refuses, the provider holds that refusal and every request this session fails with it.
 *
 * A failure stays a failure. [SemanticGrader] returns it as the reply, the session pauses
 * on it with the card kept, and the learner self-grades; nothing here turns it into a
 * label or a rating.
 *
 * [grade] blocks for up to two provider attempts, so it is only ever called on the
 * grading worker. [cancel] is called on the session thread and is handed to that same
 * worker, because [SemanticGrader] is not thread-safe; a reply that lands before the
 * withdrawal does is still dropped by the session's revision check.
 */
internal class StudyGrader(
    private val provider: GradingProvider,
    private val sessionId: String,
    private val revision: AtomicInteger,
    private val gradingWorker: Executor,
) : Grader {
    private val semantic = SemanticGrader(provider, sessionId) { revision.get() }

    /** Touched only on the grading worker. */
    private var routeChecked = false

    override fun grade(request: GradingRequest): GradingReply {
        RuleGrader.grade(request.context)?.let { return GradingReply(request, it) }
        if (!routeChecked) {
            routeChecked = true
            provider.startSession(sessionId)
        }
        return semantic.grade(request)
    }

    override fun cancel(request: GradingRequest) {
        gradingWorker.execute { semantic.cancel(request) }
    }
}
