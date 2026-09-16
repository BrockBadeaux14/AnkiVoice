package org.ankivoice.core.commands

import org.ankivoice.core.contracts.Confidence

/**
 * AV-014: where a command may resolve.
 *
 * The contexts are **disjoint from AV-012's answer window** by construction. Inside that
 * window every utterance is answer text, so "repeat the experiment", "pause the reaction"
 * and "skip a generation" are graded as answers rather than executed. There is no wake
 * word and no prefix keyword: the context alone disambiguates, which is why nothing here
 * ever removes a token from a transcript.
 */
enum class CommandContext(val specName: String) {
    /** AV-012's active capture. No utterance is parsed, and no token is stripped. */
    ANSWER("answer"),

    /** A learner-opened command capture at an idle, thinking, grading or paused point. */
    COMMAND("command"),

    /** The pre-commit exchange, where a pending rating is confirmed or changed. */
    CONFIRMATION("confirmation"),

    /**
     * Nothing may resolve: playback is in flight, the single write is in flight, or the
     * session halted somewhere no command may leave. `outcome-unknown` is one of them, so
     * no command can clear the obligation to reconcile an ambiguous write.
     */
    UNAVAILABLE("unavailable"),
}

/**
 * Which contexts a command resolves in.
 *
 * A separate enum rather than a set literal on each [VoiceCommand], so the vocabulary's
 * class initializer touches only [CommandContext] and never this file's own top-level
 * phrase table — which is built *from* the vocabulary.
 */
enum class CommandScope(val contexts: Set<CommandContext>) {
    /** Everywhere a command may be heard: both contexts outside the answer window. */
    OUTSIDE_ANSWER(setOf(CommandContext.COMMAND, CommandContext.CONFIRMATION)),
    COMMAND_ONLY(setOf(CommandContext.COMMAND)),
    CONFIRMATION_ONLY(setOf(CommandContext.CONFIRMATION)),
}

/**
 * AV-014's command vocabulary. Parsing takes no platform types and reaches the recognizer
 * only through AV-007's `SpeechInput`, so `:core` stays pure Kotlin/JVM.
 *
 * [guarded] marks the commands that advance past the card, reveal the answer, or propose
 * or confirm a rating. Recognition below [Confidence.SUFFICIENT] never executes one. The
 * rest — repeat, pause and change — only replay audio, stop the microphone or reopen a
 * choice, so a misrecognition costs nothing irreversible and always moves toward safety.
 * That distinction is load-bearing: the pinned recognizer reported **absent** confidence
 * throughout AV-013's live check, so a blanket confidence gate would leave no command
 * usable by voice at all.
 *
 * A rating command is a *proposal*. Nothing in this file writes, rates, buries, suspends
 * or reorders anything, and no command path reaches the writer.
 */
enum class VoiceCommand(
    val specName: String,
    val scope: CommandScope,
    /** True when recognition below [Confidence.SUFFICIENT] must never execute it. */
    val guarded: Boolean,
    /** The rating this command proposes, or null when it proposes none. */
    val rating: Int? = null,
    /** True for a command only an on-screen control may execute. */
    val touchOnly: Boolean = false,
) {
    /** Replay the Prompt. Question audio only — never the ReferenceAnswer or the Extra. */
    REPEAT("repeat", CommandScope.OUTSIDE_ANSWER, guarded = false),

    /** Speak the ReferenceAnswer, which AV-007 permits only after an answer exists. */
    REVEAL("reveal", CommandScope.OUTSIDE_ANSWER, guarded = true),

    /** Stop capture, release the recognizer, keep the card, write nothing. */
    PAUSE("pause", CommandScope.OUTSIDE_ANSWER, guarded = false),

    /**
     * Discard the turn and re-query a fresh card.
     *
     * Touch only. A pause releases the recognizer and opens no idle listening, so there is
     * no open microphone for a spoken "resume" to arrive on; offering one would contradict
     * AV-012's zero automatic re-arms and AV-025's single-active-capture rule.
     */
    RESUME("resume", CommandScope.COMMAND_ONLY, guarded = false, touchOnly = true),

    /** End the session. Nothing is written, rated, buried or suspended. */
    FINISH_SESSION("finish-session", CommandScope.OUTSIDE_ANSWER, guarded = true),

    /** Pause or exit without any write. AV-004 found no non-mutating skip operation. */
    SKIP("skip", CommandScope.OUTSIDE_ANSWER, guarded = true),

    RATE_AGAIN("rate-again", CommandScope.OUTSIDE_ANSWER, guarded = true, rating = 1),
    RATE_HARD("rate-hard", CommandScope.OUTSIDE_ANSWER, guarded = true, rating = 2),
    RATE_GOOD("rate-good", CommandScope.OUTSIDE_ANSWER, guarded = true, rating = 3),
    RATE_EASY("rate-easy", CommandScope.OUTSIDE_ANSWER, guarded = true, rating = 4),

    /** Authorize the pending rating. It authorizes; it does not submit. */
    CONFIRM("confirm", CommandScope.CONFIRMATION_ONLY, guarded = true),

    /** Reject the pending rating and reopen the choice. Nothing is submitted either way. */
    CHANGE("change", CommandScope.CONFIRMATION_ONLY, guarded = false),
    ;

    /** The contexts this command resolves in. Never [CommandContext.ANSWER]. */
    val contexts: Set<CommandContext> get() = scope.contexts

    /** True when this command may resolve in [context]. */
    fun resolvesIn(context: CommandContext): Boolean = context in contexts
}

/**
 * The spoken phrases, per command.
 *
 * Whole-utterance phrases only. A substring match would let "skip a generation" fire skip
 * the moment the learner opened a command capture to say something else, and the touch
 * fallback already covers everything a looser match would buy.
 *
 * "again" is deliberately listed under both [VoiceCommand.REPEAT] and
 * [VoiceCommand.RATE_AGAIN]: it is the Anki rating's name *and* the ordinary English word
 * for doing something over. It therefore parses as ambiguous and executes nothing.
 */
private val PHRASES: Map<VoiceCommand, List<String>> = mapOf(
    VoiceCommand.REPEAT to
        listOf("repeat", "repeat that", "repeat the question", "say it again", "read it again", "again"),
    VoiceCommand.REVEAL to
        listOf("reveal", "reveal the answer", "show answer", "show the answer", "what is the answer"),
    VoiceCommand.PAUSE to listOf("pause", "pause study", "pause the session"),
    VoiceCommand.RESUME to listOf("resume", "resume the session", "continue"),
    VoiceCommand.FINISH_SESSION to
        listOf("finish session", "finish the session", "end session", "end the session", "stop studying"),
    VoiceCommand.SKIP to listOf("skip", "skip this card", "skip the card", "next card"),
    VoiceCommand.RATE_AGAIN to listOf("rate again", "mark again", "again"),
    VoiceCommand.RATE_HARD to listOf("hard", "rate hard", "mark hard"),
    VoiceCommand.RATE_GOOD to listOf("good", "rate good", "mark good"),
    VoiceCommand.RATE_EASY to listOf("easy", "rate easy", "mark easy"),
    VoiceCommand.CONFIRM to listOf("confirm", "confirm the rating", "yes", "submit"),
    VoiceCommand.CHANGE to listOf("change", "change it", "change the rating", "no"),
)

/** Every phrase, mapped to the commands it could mean. A phrase with two meanings is ambiguous. */
val COMMAND_PHRASES: Map<String, Set<VoiceCommand>> = run {
    val table = linkedMapOf<String, MutableSet<VoiceCommand>>()
    for ((command, phrases) in PHRASES) {
        for (phrase in phrases) table.getOrPut(phrase) { linkedSetOf() } += command
    }
    table.mapValues { (_, commands) -> commands.toSet() }
}

/** What the parser made of one utterance. Only [CommandRecognition.Recognized] may be executed. */
sealed interface CommandRecognition {
    /**
     * AV-012's answer window. [text] is the utterance **unchanged**: nothing is stripped,
     * trimmed or rewritten, so a command word spoken as part of an answer is graded as
     * the answer text it is.
     */
    data class AnswerText(val text: String) : CommandRecognition

    data class Recognized(val command: VoiceCommand, val phrase: String) : CommandRecognition

    /** More than one command shares the phrase. Nothing runs; the learner is asked again. */
    data class Ambiguous(val candidates: List<VoiceCommand>, val phrase: String) : CommandRecognition

    /** A guarded command heard below [Confidence.SUFFICIENT]. It is never executed. */
    data class Uncertain(
        val command: VoiceCommand,
        val confidence: Confidence,
        val phrase: String,
    ) : CommandRecognition

    /** In the vocabulary, but not in this context. */
    data class OutOfContext(
        val command: VoiceCommand,
        val context: CommandContext,
        val phrase: String,
    ) : CommandRecognition

    /** Nothing in the vocabulary matched the whole utterance. */
    data class Unrecognized(val text: String) : CommandRecognition
}

/**
 * The parser. It holds no state, takes no platform types and never touches a session.
 *
 * [parse] is the only place a spoken utterance becomes a command, and its first rule is
 * the context rule: in [CommandContext.ANSWER] it returns the text and stops.
 */
object CommandVocabulary {

    /** The whole vocabulary, in declaration order. */
    val commands: List<VoiceCommand> get() = VoiceCommand.entries

    /**
     * Lower-case, replace every non-alphanumeric run with one space, and trim.
     *
     * It works on a copy and is used only to look a phrase up. The transcript AV-012 holds
     * is never normalized, edited or re-spelled by this file.
     */
    fun normalize(text: String): String = text.lowercase().replace(NON_WORD, " ").trim()

    fun parse(text: String, context: CommandContext, confidence: Confidence): CommandRecognition {
        // The context rule, first and unconditionally: inside the answer window there is no
        // command vocabulary at all.
        if (context == CommandContext.ANSWER) return CommandRecognition.AnswerText(text)
        val phrase = normalize(text)
        val candidates = COMMAND_PHRASES[phrase] ?: return CommandRecognition.Unrecognized(text)
        if (candidates.size > 1) return CommandRecognition.Ambiguous(candidates.toList(), phrase)
        val command = candidates.first()
        if (!command.resolvesIn(context)) return CommandRecognition.OutOfContext(command, context, phrase)
        if (command.guarded && confidence != Confidence.SUFFICIENT) {
            return CommandRecognition.Uncertain(command, confidence, phrase)
        }
        return CommandRecognition.Recognized(command, phrase)
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
