package org.ankivoice.provider

/**
 * The runtime credential. It is entered on a settings screen, never built into the APK:
 * it appears in no `BuildConfig` field, resource, asset or log, and the UI never shows it
 * again after entry — only whether one is saved.
 */
interface CredentialStore {
    /** The key, or null when none is saved or the stored value cannot be unwrapped. */
    fun read(): String?

    /** Validates and stores. False means the value is not an OpenRouter key; nothing is stored. */
    fun save(key: String): Boolean

    fun clear()

    fun present(): Boolean = read() != null
}

/** Key shape and log redaction, ported from AV-006's `validate_key`. */
object CredentialPolicy {
    const val PREFIX: String = "sk-or-"
    const val MIN_LENGTH: Int = 30

    fun valid(key: String): Boolean {
        val trimmed = key.trim()
        return trimmed.startsWith(PREFIX) && trimmed.length >= MIN_LENGTH && trimmed.none(Char::isWhitespace)
    }

    /** What a settings screen may show: that a key exists, never any part of it. */
    fun describe(present: Boolean): String =
        if (present) "A key is saved on this device." else "No key is saved."

    /**
     * Removes anything key-shaped from text that is about to be recorded or displayed.
     * Diagnostics pass every detail through this, so a provider message that quotes the
     * key cannot reach the log.
     */
    fun redact(text: String): String = KEY_PATTERN.replace(text, "$PREFIX…redacted")

    private val KEY_PATTERN = Regex("${Regex.escape(PREFIX)}[A-Za-z0-9._\\-]+")
}

/** A store for tests and for a build with no Keystore; never used by the app. */
internal class InMemoryCredentialStore(private var key: String? = null) : CredentialStore {
    override fun read(): String? = key

    override fun save(key: String): Boolean {
        if (!CredentialPolicy.valid(key)) return false
        this.key = key.trim()
        return true
    }

    override fun clear() {
        key = null
    }
}
