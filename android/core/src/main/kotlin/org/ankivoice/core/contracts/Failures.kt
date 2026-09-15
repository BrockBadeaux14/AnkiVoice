package org.ankivoice.core.contracts

/** The five AV-007 contracts. Each owns one group of enumerated failures. */
enum class Contract {
    CardProvider,
    SpeechOutput,
    SpeechInput,
    Grader,
    ReviewWriter,
}

/**
 * A transport or provider fault. Never evidence about the learner's answer.
 *
 * Sealed over one enum per contract, so a `when` over a mode is checked exhaustively
 * first by contract and then by failure. [specName] is the specification's spelling.
 */
sealed interface FailureMode {
    val contract: Contract
    val specName: String
}

enum class CardProviderFailure(override val specName: String) : FailureMode {
    /** Permission never granted or revoked. */
    ACCESS_DENIED("accessDenied"),

    /** Null cursor: AnkiDroid API switched off. */
    API_DISABLED("apiDisabled"),

    /** Null cursor: AnkiDroid disabled or absent. */
    PACKAGE_UNAVAILABLE("packageUnavailable"),

    /** Not exhaustion; the deck itself is gone. */
    DECK_MISSING("deckMissing"),

    /** The offered card no longer exists. */
    CARD_NOT_FOUND("cardNotFound"),

    /** Replaced, restored or synced underneath the session. */
    COLLECTION_CHANGED("collectionChanged"),
    UNSUPPORTED_NOTE_TYPE("unsupportedNoteType"),

    /** Required VoiceQA fields missing. */
    MALFORMED_CARD("malformedCard"),

    /** Cause not established. */
    NULL_CURSOR("nullCursor"),
    ;

    override val contract: Contract get() = Contract.CardProvider
}

enum class SpeechOutputFailure(override val specName: String) : FailureMode {
    ENGINE_UNAVAILABLE("engineUnavailable"),
    LANGUAGE_UNSUPPORTED("languageUnsupported"),
    AUDIO_FOCUS_LOST("audioFocusLost"),
    PLAYBACK_INTERRUPTED("playbackInterrupted"),
    ;

    override val contract: Contract get() = Contract.SpeechOutput
}

enum class SpeechInputFailure(override val specName: String) : FailureMode {
    PERMISSION_DENIED("permissionDenied"),
    RECOGNIZER_UNAVAILABLE("recognizerUnavailable"),
    RECOGNIZER_ERROR("recognizerError"),

    /** Silence, not a wrong answer. */
    NO_SPEECH_DETECTED("noSpeechDetected"),
    LISTEN_TIMEOUT("listenTimeout"),
    NETWORK_UNAVAILABLE("networkUnavailable"),
    QUOTA_EXHAUSTED("quotaExhausted"),

    /** Overloaded Android `ERROR_NO_MATCH`; its cause stays unknown. */
    NO_MATCH("noMatch"),

    /** Capture ended without a usable final. */
    EARLY_CLOSURE("earlyClosure"),

    /** Low or absent confidence. */
    LOW_CONFIDENCE("lowConfidence"),
    ;

    override val contract: Contract get() = Contract.SpeechInput
}

enum class GraderFailure(override val specName: String) : FailureMode {
    QUOTA_EXHAUSTED("quotaExhausted"),
    PROVIDER_ERROR("providerError"),
    GRADER_TIMEOUT("graderTimeout"),
    UNPARSABLE_RESPONSE("unparsableResponse"),
    OUTPUT_TRUNCATED("outputTruncated"),
    ;

    override val contract: Contract get() = Contract.Grader
}

enum class ReviewWriterFailure(override val specName: String) : FailureMode {
    /** Outside the ratings offered for this card. */
    RATING_REJECTED("ratingRejected"),

    /** Negative elapsed time. */
    INVALID_REVIEW_TIME("invalidReviewTime"),

    /** Identity or stored state moved. */
    STALE_IDENTITY("staleIdentity"),
    PRECOMMIT_READ_FAILED("precommitReadFailed"),

    /** Explicit zero with an unchanged card. */
    WRITE_REJECTED("writeRejected"),
    CONFIRMATION_REQUIRED("confirmationRequired"),
    ;

    override val contract: Contract get() = Contract.ReviewWriter
}

/** The failures a contract can report, in declaration order. */
fun failureModes(contract: Contract): List<FailureMode> = when (contract) {
    Contract.CardProvider -> CardProviderFailure.entries
    Contract.SpeechOutput -> SpeechOutputFailure.entries
    Contract.SpeechInput -> SpeechInputFailure.entries
    Contract.Grader -> GraderFailure.entries
    Contract.ReviewWriter -> ReviewWriterFailure.entries
}

/** All 34 enumerated failures, grouped by contract. */
val ALL_FAILURE_MODES: List<FailureMode> = Contract.entries.flatMap(::failureModes)

/**
 * A failed operation. A Failure is never convertible into a rating: it carries a mode,
 * a detail and an optional cause, and nothing in :core maps it to one.
 */
data class Failure(
    val mode: FailureMode,
    val detail: String = "",
    val cause: Failure? = null,
) : CapabilitiesResult, ReadCardResult, GradingOutcome {
    val contract: Contract get() = mode.contract

    override fun toString(): String {
        val text = "${contract.name}.${mode.specName}"
        return if (detail.isEmpty()) text else "$text: $detail"
    }
}
